"""Owner-scoped Hub task consumer for the Windows companion.

The Hub remains authoritative for policy, scheduling, leases, and audit. This
module executes only the two actions in the versioned device-task contract.
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import UUID

from el_agent.actions import capture_screenshot, launch_app
from el_agent.hub import (
    HEARTBEAT_REFRESH_WINDOW_SECONDS,
    HubClientError,
    _refresh_credentials,
    _request_json,
    load_credentials,
)
from el_agent.service import screenshot_dir

logger = logging.getLogger(__name__)
POLL_INTERVAL_SECONDS = 2
MAX_TASK_TIMEOUT_SECONDS = 60
MAX_TASK_LEASE_SECONDS = 60
ALLOWED_SCOPES = {"device.heartbeat", "app.launch", "screen.capture"}
ALLOWED_ACTIONS = {"app.launch", "screen.capture"}
ERROR_CODES = {"app_not_available", "permission_denied", "timeout", "execution_failed"}


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _active_credentials() -> dict[str, Any] | None:
    credentials = load_credentials()
    if credentials is None:
        return None
    scopes = credentials.get("scopes")
    if not isinstance(scopes, list) or not set(scopes).issubset(ALLOWED_SCOPES):
        raise HubClientError("Stored Hub scopes are invalid; remote task polling is disabled.")
    if "device.heartbeat" not in scopes:
        raise HubClientError("The paired device lacks its required heartbeat scope.")
    from datetime import datetime, timezone

    expiry = datetime.fromisoformat(str(credentials["access_expires_at"]))
    if expiry <= datetime.now(timezone.utc) + timedelta(seconds=HEARTBEAT_REFRESH_WINDOW_SECONDS):
        credentials = _refresh_credentials(credentials)
    return credentials


def _post(credentials: dict[str, Any], path: str, payload: dict[str, Any]) -> dict[str, Any]:
    return _request_json(
        str(credentials["hub_url"]),
        path,
        method="POST",
        payload=payload,
        headers={
            "Authorization": "Bearer " + str(credentials["access_token"]),
            "X-Device-ID": str(credentials["device_id"]),
        },
    )


def claim_one() -> dict[str, Any] | None:
    credentials = _active_credentials()
    if credentials is None:
        return None
    result = _post(credentials, "/devices/tasks/claim", {})
    task = result.get("task")
    if task is None:
        return None
    if not isinstance(task, dict):
        raise HubClientError("Hub returned an invalid device task.")
    lease = result.get("lease_token")
    if not isinstance(lease, str) or not 40 <= len(lease) <= 128:
        raise HubClientError("Hub returned an invalid task lease.")
    lease_expiry_value = result.get("lease_expires_at")
    if not isinstance(lease_expiry_value, str):
        raise HubClientError("Hub omitted the task lease expiry.")
    try:
        lease_expiry = datetime.fromisoformat(lease_expiry_value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise HubClientError("Hub returned an invalid task lease expiry.") from exc
    if lease_expiry.tzinfo is None or lease_expiry.utcoffset() is None:
        raise HubClientError("Hub task lease expiry must include a timezone.")
    remaining = (lease_expiry - _utc_now()).total_seconds()
    if not 0 < remaining <= MAX_TASK_LEASE_SECONDS:
        raise HubClientError("Hub task lease is expired or exceeds the supported bound.")
    return {
        "credentials": credentials,
        "task": task,
        "lease_token": lease,
        "lease_expires_at": lease_expiry.isoformat(),
    }


def _execute(task: dict[str, Any]) -> None:
    action = task.get("action")
    if action not in ALLOWED_ACTIONS:
        raise ValueError("Unsupported action.")
    if action == "app.launch":
        alias = task.get("app")
        if not isinstance(alias, str):
            raise ValueError("Application alias is missing.")
        launch_app(alias)
        return
    if action == "screen.capture":
        if task.get("owner_confirmed") is not True:
            raise PermissionError("Screenshot task lacks explicit owner confirmation.")
        capture_screenshot(screenshot_dir())
        return


def execute_claimed(claimed: dict[str, Any]) -> dict[str, Any]:
    task = claimed["task"]
    credentials = claimed["credentials"]
    task_id = task.get("task_id")
    required_scope = task.get("required_scope")
    scopes = credentials.get("scopes", [])
    action = task.get("action")

    if not isinstance(task_id, str):
        raise HubClientError("Hub task identity is invalid.")
    try:
        if str(UUID(task_id)) != task_id:
            raise ValueError("Task identity is not canonical.")
        lease_value = claimed.get("lease_expires_at")
        if not isinstance(lease_value, str):
            raise ValueError("Task lease expiry is missing.")
        lease_expires_at = datetime.fromisoformat(lease_value.replace("Z", "+00:00"))
        if lease_expires_at.tzinfo is None or lease_expires_at.utcoffset() is None:
            raise ValueError("Task lease expiry must include a timezone.")
    except (ValueError, TypeError) as exc:
        raise HubClientError("Hub task identity or lease contract is invalid.") from exc
    if task.get("device_id") != credentials.get("device_id"):
        raise HubClientError("Hub returned a task for a different device identity.")

    if not _utc_now() < lease_expires_at:
        return {
            "task_id": task_id,
            "reported_status": "not_reported_lease_expired",
            "outcome_verified": None,
            "action_attempted": False,
        }

    expected_scope = (
        "app.launch" if action == "app.launch"
        else "screen.capture" if action == "screen.capture"
        else None
    )
    timeout = task.get("timeout_seconds")
    scope_allowed = (
        expected_scope is not None
        and required_scope == expected_scope
        and isinstance(scopes, list)
        and required_scope in scopes
    )
    timeout_valid = (
        not isinstance(timeout, bool)
        and isinstance(timeout, int)
        and 5 <= timeout <= MAX_TASK_TIMEOUT_SECONDS
    )
    owner_confirmed = action != "screen.capture" or task.get("owner_confirmed") is True

    outcome = "succeeded"
    error_code = None
    action_started = False
    started = time.monotonic()
    if not scope_allowed:
        # Deny locally even if an upstream contract unexpectedly returns a
        # task without the exact capability. Report the denial over the
        # mandatory heartbeat scope so Hub audit can record the decision.
        outcome, error_code = "failed", "permission_denied"
    elif not timeout_valid:
        outcome, error_code = "failed", "execution_failed"
    elif not owner_confirmed:
        outcome, error_code = "failed", "permission_denied"
    else:
        try:
            action_started = True
            _execute(task)
        except PermissionError:
            outcome, error_code = "failed", "permission_denied"
        except TimeoutError:
            outcome, error_code = "failed", "timeout"
        except Exception:
            logger.exception("Hub device task failed")
            outcome, error_code = "failed", "execution_failed"

    duration_ms = min(60_000, max(0, int((time.monotonic() - started) * 1000)))

    # An action may have taken effect even if it ran beyond its lease. Never
    # report a late success/failure or replay it; Hub will reconcile expiry as
    # unknown_outcome. This is safer than treating delivery as exactly-once.
    if _utc_now() >= lease_expires_at:
        return {
            "task_id": task_id,
            "reported_status": "not_reported_lease_expired",
            "outcome_verified": None,
            "action_attempted": action_started,
        }

    payload: dict[str, Any] = {
        "lease_token": claimed["lease_token"],
        "outcome": outcome,
        "duration_ms": duration_ms,
    }
    if error_code:
        payload["error_code"] = error_code
    reported = _post(credentials, f"/devices/tasks/{task_id}/complete", payload)
    reported_task = reported.get("task")
    if not isinstance(reported_task, dict):
        raise HubClientError("Hub did not confirm that the device outcome was recorded.")
    return {
        "task_id": task_id,
        "reported_status": reported_task.get("status"),
        "outcome_verified": reported_task.get("outcome_verified"),
        "action_attempted": action_started,
    }


async def hub_task_loop() -> None:
    """Poll only while paired; never replay an expired/ambiguous claim."""
    while True:
        try:
            claimed = await asyncio.to_thread(claim_one)
            if claimed is not None:
                result = await asyncio.to_thread(execute_claimed, claimed)
                if result["reported_status"] == "not_reported_lease_expired":
                    logger.warning(
                        "Hub task lease expired before completion reporting: task_id=%s action_attempted=%s; Hub will reconcile as unknown_outcome and the Agent will not replay it.",
                        result["task_id"], result["action_attempted"],
                    )
                else:
                    logger.info(
                        "Hub task outcome recorded: task_id=%s status=%s verified=%s",
                        result["task_id"], result["reported_status"], result["outcome_verified"],
                    )
        except HubClientError as exc:
            logger.warning("Hub task consumer unavailable: %s", exc)
        except Exception:
            logger.exception("Unexpected Hub task consumer failure")
        await asyncio.sleep(POLL_INTERVAL_SECONDS)
