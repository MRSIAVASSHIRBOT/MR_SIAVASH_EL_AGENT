"""Local runtime paths and durable scheduler."""
from __future__ import annotations
import asyncio
import logging
import os
from datetime import datetime, timezone
from pathlib import Path
from el_agent.actions import launch_app
from el_agent.store import TaskStore
logger = logging.getLogger(__name__)

def data_dir() -> Path:
    base = os.environ.get("LOCALAPPDATA")
    return Path(base) / "MR_SIAVASH_EL_AGENT" if base else Path.home() / ".mr_siavash_el_agent"

def screenshot_dir() -> Path:
    return data_dir() / "screenshots"

def task_store() -> TaskStore:
    return TaskStore(data_dir() / "tasks.sqlite3")

async def scheduler_loop(store: TaskStore) -> None:
    # A process restart cannot prove whether a previously claimed launch took
    # effect. Fence those rows as unknown instead of replaying them.
    try:
        recovered = await asyncio.to_thread(store.reconcile_interrupted)
        if recovered:
            logger.warning(
                "Local scheduler reconciled %s interrupted task(s) as unknown_outcome; none were replayed.",
                recovered,
            )
    except Exception:
        logger.exception("Local scheduler startup recovery failed")

    while True:
        try:
            due_tasks = await asyncio.to_thread(store.claim_due, datetime.now(timezone.utc))
            for task in due_tasks:
                try:
                    await asyncio.to_thread(launch_app, task["app_alias"])
                    await asyncio.to_thread(store.finish, task["task_id"])
                except asyncio.CancelledError:
                    # Leave the row running. On the next process start, recovery
                    # will classify the outcome as unknown rather than replay it.
                    raise
                except Exception as exc:
                    logger.exception("Scheduled task failed: %s", task["task_id"])
                    await asyncio.to_thread(
                        store.finish,
                        task["task_id"],
                        type(exc).__name__ + ": " + str(exc),
                    )
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("Scheduler iteration failed")
        await asyncio.sleep(1)


async def hub_heartbeat_loop() -> None:
    """Synchronize only minimal device health metadata with the paired Hub."""
    from el_agent.hub import HEARTBEAT_INTERVAL_SECONDS, HubClientError, heartbeat_once

    while True:
        try:
            await asyncio.to_thread(heartbeat_once)
        except HubClientError as exc:
            logger.warning("Hub heartbeat unavailable: %s", exc)
        except Exception:
            logger.exception("Unexpected Hub heartbeat failure")
        await asyncio.sleep(HEARTBEAT_INTERVAL_SECONDS)


async def hub_task_loop() -> None:
    """Consume Hub-authorized device tasks with bounded action contracts."""
    from el_agent.remote_tasks import hub_task_loop as run_remote_tasks
    await run_remote_tasks()
