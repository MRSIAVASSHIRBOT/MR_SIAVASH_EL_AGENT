package ir.mrsiavash.elagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.view.ViewGroup
import android.widget.Button
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val plannerExecutor = Executors.newSingleThreadExecutor()
    private lateinit var store: SecureStore
    private lateinit var status: TextView
    private lateinit var fatherContactStatus: TextView
    private lateinit var hubUrl: EditText
    private lateinit var pairingCode: EditText
    private lateinit var voiceController: LocalVoiceController
    private lateinit var voiceButton: Button
    private var voiceActive = false
    private val recordAudioRequest = 4102
    private val fatherContactRequest = 4105
    private val callPhoneRequest = 4106
    private var pendingCallAfterContactSelection = false
    private var pendingCallAfterPermission = false
    private val openDocumentRequest = 4103
    private lateinit var documentPreview: TextView
    private lateinit var plannerRequest: EditText
    private lateinit var plannerButton: Button
    private lateinit var plannerOutput: TextView
    private lateinit var executionStatus: TextView
    private lateinit var executionNextButton: Button
    private lateinit var executionVerifyButton: Button
    private var executionSession: AgentPlanExecutionSession? = null
    private var plannerFailureMessage: String? = "هنوز طرحی ساخته نشده است."
    private var pendingDocumentStepId: String? = null
    private var activityInForeground = false
    private val executionDocumentRequest = 4104

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 16, 12))
        }
        val pageHost = android.widget.FrameLayout(this)
        fun createPage(): Pair<ScrollView, LinearLayout> {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 28, 22, 28)
                setBackgroundColor(Color.rgb(7, 22, 16))
            }
            val page = ScrollView(this).apply {
                isFillViewport = true
                addView(content)
            }
            pageHost.addView(page, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
            return page to content
        }
        val (homePage, homeContent) = createPage()
        val (aiPage, aiContent) = createPage()
        val (toolsPage, toolsContent) = createPage()
        val (settingsPage, settingsContent) = createPage()
        val pages = listOf(homePage, aiPage, toolsPage, settingsPage)
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 12)
            setBackgroundColor(Color.rgb(8, 28, 20))
        }
        val navigationButtons = mutableListOf<Button>()
        fun selectPage(index: Int) {
            pages.forEachIndexed { pageIndex, page ->
                page.visibility = if (pageIndex == index) ViewGroup.VISIBLE else ViewGroup.GONE
            }
            navigationButtons.forEachIndexed { buttonIndex, button ->
                val selected = buttonIndex == index
                button.backgroundTintList = ColorStateList.valueOf(
                    if (selected) Color.rgb(20, 185, 129) else Color.rgb(13, 43, 30)
                )
                button.setTextColor(if (selected) Color.BLACK else Color.rgb(190, 220, 204))
            }
        }
        listOf("خانه", "هوش مصنوعی", "ابزارها", "اتصال").forEachIndexed { index, label ->
            val button = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setPadding(2, 8, 2, 8)
                setOnClickListener { selectPage(index) }
            }
            navigationButtons += button
            navigation.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        shell.addView(pageHost, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        shell.addView(navigation, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        setContentView(shell)
        selectPage(0)
        var root: LinearLayout = homeContent
        homeContent.addView(TextView(this).apply {
            text = "دستیار شخصی هوشمند"
            textSize = 26f
            setTextColor(Color.rgb(236, 250, 242))
        }, fieldParams())
        homeContent.addView(TextView(this).apply {
            text = "MR SIAVASH EL AGENT  ·  کنترل‌پنل Android"
            textSize = 13f
            setTextColor(Color.rgb(88, 218, 165))
        }, fieldParams())

        root.addView(TextView(this).apply {
            text = "MR SIAVASH EL AGENT"
            textSize = 24f
            setTextColor(Color.rgb(232, 245, 239))
        })
        root.addView(TextView(this).apply {
            text = "Android companion · v0.5.0"
            textSize = 14f
            setTextColor(Color.rgb(168, 196, 183))
        })
        root.addView(TextView(this).apply {
            text = "Device ID: ${store.deviceId()}"
            textSize = 12f
            setTextColor(Color.rgb(53, 214, 160))
            setTextIsSelectable(true)
        }, fieldParams())
        root = settingsContent
        root.addView(TextView(this).apply {
            text = "اتصال دستگاه و مخاطب سریع"
            textSize = 19f
            setTextColor(Color.rgb(88, 218, 165))
        }, fieldParams())
        root.addView(Button(this).apply {
            text = "انتخاب مخاطب پدر از مخاطبین گوشی"
            isAllCaps = false
            backgroundTintList = ColorStateList.valueOf(Color.rgb(15, 65, 43))
            setTextColor(Color.rgb(225, 248, 235))
            setOnClickListener { chooseFatherContact() }
        }, buttonParams())
        fatherContactStatus = TextView(this).apply {
            text = fatherContactSummary()
            textSize = 13f
            setTextColor(Color.rgb(190, 215, 199))
        }
        root.addView(fatherContactStatus, fieldParams())
        hubUrl = EditText(this).apply {
            hint = "Hub HTTPS address (https://...)"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        pairingCode = EditText(this).apply {
            hint = "One-time pairing code"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        root.addView(hubUrl, fieldParams())
        root.addView(pairingCode, fieldParams())
        root.addView(Button(this).apply {
            text = "Pair with Hub"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener {
                val url = hubUrl.text.toString()
                val code = pairingCode.text.toString()
                setBusy("Pairing…")
                executor.execute {
                    runCatching { HubClient(store).pair(url, code) }
                        .onSuccess { message -> runOnUiThread { pairingCode.text.clear(); setStatus(message); scheduleHeartbeat() } }
                        .onFailure { error -> runOnUiThread { setStatus(error.message ?: "Pairing failed.") } }
                }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "Send heartbeat now"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener {
                setBusy("Contacting Hub…")
                executor.execute {
                    runCatching { HubClient(store).heartbeat() }
                        .onSuccess { message -> runOnUiThread { setStatus(message) } }
                        .onFailure { error -> runOnUiThread { setStatus(error.message ?: "Heartbeat failed.") } }
                }
            }
        }, buttonParams())
        root = homeContent
        voiceButton = Button(this).apply {
            text = "🎙  صحبت با سیا"
            textSize = 18f
            isAllCaps = false
            minHeight = 72
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener { toggleVoice() }
        }
        root.addView(voiceButton, buttonParams())
        root.addView(TextView(this).apply {
            text = "برای فرمان صوتی، دکمه را بزن و بعد از شنیدن صدای آماده‌باش صحبت کن. تشخیص گفتار فعلاً با موتور آفلاین Android انجام می‌شود."
            textSize = 12f
            setTextColor(Color.rgb(176, 204, 188))
        }, fieldParams())
        root = toolsContent
        root.addView(TextView(this).apply {
            text = "ابزارهای دستگاه"
            textSize = 21f
            setTextColor(Color.rgb(88, 218, 165))
        }, fieldParams())
        root.addView(Button(this).apply {
            text = "Open ChatGPT"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                runCatching { launchAllowlisted("chatgpt") }
                    .onFailure { setStatus(it.message ?: "Could not open ChatGPT.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "Open browser"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                runCatching { launchAllowlisted("browser") }
                    .onFailure { setStatus(it.message ?: "Could not open browser.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "انتخاب و پیش‌نمایش فایل متنی"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            setOnClickListener {
                runCatching {
                    startActivityForResult(AndroidDocumentAccess.createOpenDocumentIntent(), openDocumentRequest)
                }.onFailure { setStatus(it.message ?: "بازکردن انتخاب‌گر فایل ناموفق بود.") }
            }
        }, buttonParams())
        documentPreview = TextView(this).apply {
            text = "فایل فقط پس از انتخاب مستقیم شما خوانده می‌شود. پیش‌نمایش متنی حداکثر ۶۴ کیلوبایت است و به Cloud ارسال نمی‌شود."
            textSize = 13f
            setTextColor(Color.rgb(210, 226, 217))
            setTextIsSelectable(true)
        }
        root.addView(documentPreview, fieldParams())
        root = aiContent
        root.addView(TextView(this).apply {
            text = "هوش مصنوعی و برنامه‌ریز"
            textSize = 21f
            setTextColor(Color.rgb(88, 218, 165))
        }, fieldParams())
        root.addView(TextView(this).apply {
            text = "برنامه‌ریز هوشمند · تبدیل درخواست به مراحل ساختاریافته"
            textSize = 17f
            setTextColor(Color.rgb(53, 214, 160))
        }, fieldParams())
        root.addView(TextView(this).apply {
            text = "ارائه‌دهنده‌های رایگان · Gemini سپس OpenRouter"
            textSize = 14f
            setTextColor(Color.rgb(232, 245, 239))
        }, fieldParams())
        root.addView(TextView(this).apply {
            text = "کلید هر سرویس اختیاری است و فقط در محفظهٔ رمزگذاری‌شدهٔ Android ذخیره می‌شود. برنامه فقط بین مسیرهای رایگان جابه‌جا می‌شود و هیچ مسیر پولی یا ارتقای خودکاری ندارد. در صورت خطا، متن درخواست ممکن است برای سرویس بعدی هم ارسال شود؛ اطلاعات حساس وارد نکنید. رایگان‌بودن Gemini به Tier پروژه بستگی دارد؛ برنامه وضعیت Billing حساب را نمی‌تواند تشخیص دهد. برای تضمین هزینهٔ صفر، پروژهٔ Gemini باید Free Tier و بدون Billing فعال باشد."
            textSize = 12f
            setTextColor(Color.rgb(190, 205, 196))
        }, fieldParams())
        addFreeProviderKeyEditor(root, "gemini", "Google Gemini")
        addFreeProviderKeyEditor(root, "openrouter", "OpenRouter")
        root.addView(TextView(this).apply {
            text = "مدل‌های پیش‌فرض: gemini-4-hydrogen · openrouter/free. کلید قدیمی OpenAI حذف نمی‌شود، اما برنامه‌ریز رایگان از آن استفاده نمی‌کند."
            textSize = 12f
            setTextColor(Color.rgb(190, 205, 196))
        }, fieldParams())
        root.addView(TextView(this).apply {
            text = "فقط درخواست متنی واردشده به برنامه‌ریز ارسال می‌شود؛ فایل، تصویر، صدای میکروفون، اسکرین‌شات و حافظهٔ محلی ارسال نمی‌شوند. طرح خروجی پیش از نمایش، محلی اعتبارسنجی می‌شود."
            textSize = 12f
            setTextColor(Color.rgb(190, 205, 196))
        }, fieldParams())
        plannerRequest = EditText(this).apply {
            hint = "درخواستت را بنویس؛ مثلاً: مرورگر را باز کن و این صفحه را نمایش بده."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            minLines = 3
            maxLines = 5
        }
        root.addView(plannerRequest, fieldParams())
        plannerButton = Button(this).apply {
            text = "ساخت برنامهٔ اجرایی (بدون اجرا)"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            setOnClickListener { generatePlan() }
        }
        root.addView(plannerButton, buttonParams())
        plannerOutput = TextView(this).apply {
            text = "طرح ساخته‌شده اینجا نمایش داده می‌شود. هیچ مرحله‌ای صرفاً با تولید طرح اجرا نخواهد شد."
            textSize = 14f
            setTextColor(Color.rgb(232, 245, 239))
            setTextIsSelectable(true)
        }
        root.addView(plannerOutput, fieldParams())
        executionStatus = TextView(this).apply {
            text = "پس از تولید طرح، هر مرحله با تأیید جداگانهٔ شما اجرا می‌شود."
            textSize = 13f
            setTextColor(Color.rgb(190, 205, 196))
            setTextIsSelectable(true)
        }
        root.addView(executionStatus, fieldParams())
        executionNextButton = Button(this).apply {
            text = "بررسی و اجرای مرحلهٔ بعد"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(19, 185, 129))
            setTextColor(Color.BLACK)
            visibility = ViewGroup.GONE
            setOnClickListener { confirmNextPlanStep() }
        }
        root.addView(executionNextButton, buttonParams())
        executionVerifyButton = Button(this).apply {
            text = "نتیجه را بررسی کردم؛ تکمیل مرحله"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(13, 33, 25))
            setTextColor(Color.rgb(53, 214, 160))
            visibility = ViewGroup.GONE
            setOnClickListener { confirmDispatchedStepCompletion() }
        }
        root.addView(executionVerifyButton, buttonParams())
        root = settingsContent
        root.addView(TextView(this).apply {
            text = "اتصال به MR_SIAVASH_HUB_AI"
            textSize = 19f
            setTextColor(Color.rgb(88, 218, 165))
        }, fieldParams())
        root.addView(Button(this).apply {
            text = "Unpair locally"
            backgroundTintList = ColorStateList.valueOf(Color.rgb(74, 36, 31))
            setTextColor(Color.rgb(255, 220, 210))
            setOnClickListener {
                store.clearCredentialsPreservingIdentity()
                WorkManager.getInstance(this@MainActivity).cancelUniqueWork("el-agent-heartbeat")
                setStatus("Local credentials cleared. Revoke the device in Hub to fully disconnect.")
            }
        }, buttonParams())
        root = homeContent
        status = TextView(this).apply {
            text = "آماده‌به‌کار · منتظر فرمان شما"
            textSize = 14f
            setTextColor(Color.rgb(168, 196, 183))
            setPadding(16, 18, 16, 18)
            setBackgroundColor(Color.rgb(12, 39, 27))
        }
        root.addView(status, fieldParams())
        voiceController = LocalVoiceController(this, ::setStatus, ::handleVoiceTranscript, ::finishVoiceUi)
        if (HubClient(store).isPaired()) {
            setStatus("Paired with Hub. Periodic heartbeat is scheduled.")
            scheduleHeartbeat()
        }
    }


    private fun generatePlan() {
        val requestText = plannerRequest.text.toString()
        if (requestText.isBlank() || requestText.length > 4_000) {
            setStatus("درخواست باید بین ۱ تا ۴۰۰۰ نویسه باشد.")
            return
        }
        val previous = executionSession
        if (previous != null && !previous.isComplete()) {
            AlertDialog.Builder(this)
                .setTitle("جایگزینی طرح ناتمام؟")
                .setMessage(
                    "طرح قبلی هنوز تکمیل نشده است. ممکن است یک اقدام خارجی قبلاً به Android سپرده شده باشد. " +
                        "جایگزینی، وضعیت تأییدنشدهٔ آن طرح را کنار می‌گذارد و هیچ اقدامی را تکرار نمی‌کند."
                )
                .setNegativeButton("ادامهٔ طرح قبلی", null)
                .setPositiveButton("کنارگذاشتن و ساخت طرح جدید") { _, _ ->
                    startPlanGeneration(requestText)
                }
                .show()
        } else {
            startPlanGeneration(requestText)
        }
    }

    private fun startPlanGeneration(requestText: String) {
        executionSession = null
        pendingDocumentStepId = null
        plannerFailureMessage = "در حال ساخت طرح؛ هنوز مرحله‌ای برای اجرا وجود ندارد."
        updateExecutionUi()
        plannerButton.isEnabled = false
        plannerOutput.text = "در حال ساخت طرح ساختاریافته با سرویس‌های رایگان…"
        setStatus("درخواست فقط به ارائه‌دهنده‌های رایگانِ دارای کلید ذخیره‌شده ارسال می‌شود.")
        plannerExecutor.execute {
            val result = runCatching {
                val routed = FreeProviderPlanRouter(store).generate(
                    AgentPlanPrompt.SYSTEM_PROMPT,
                    AgentPlanPrompt.userMessage(requestText)
                )
                val plan = AgentPlanParser.parse(routed.json)
                plan to routed
            }
            runOnUiThread {
                plannerButton.isEnabled = true
                result.onSuccess { (plan, routed) ->
                    executionSession = if (plan.steps.isNotEmpty()) AgentPlanExecutionSession(plan) else null
                    pendingDocumentStepId = null
                    plannerFailureMessage = if (plan.steps.isEmpty()) {
                        "طرح اجرایی ساخته نشد؛ مدل برای ادامه به توضیح بیشتر نیاز دارد."
                    } else null
                    plannerOutput.text = AgentPlanPolicy.render(plan)
                    setStatus("طرح از " + routed.provider.label + " دریافت و محلی اعتبارسنجی شد؛ هیچ مرحله‌ای هنوز اجرا نشده است.")
                    updateExecutionUi()
                }.onFailure { error ->
                    plannerFailureMessage = "طرحی برای اجرا وجود ندارد؛ ساخت طرح ناموفق بود. " +
                        (error.message ?: "لطفاً اتصال، کلیدها و سهمیهٔ سرویس‌های رایگان را بررسی کنید.")
                    plannerOutput.text = plannerFailureMessage
                    setStatus("هیچ طرح معتبری تولید نشد؛ درخواست شما در کادر ورودی باقی مانده است.")
                    updateExecutionUi()
                }
            }
        }
    }

    private fun confirmNextPlanStep() {
        val session = executionSession ?: run {
            setStatus("ابتدا یک طرح معتبر تولید کنید.")
            return
        }
        val step = session.nextRunnableStep() ?: run {
            updateExecutionUi()
            return
        }
        val position = session.plan.steps.indexOfFirst { it.id == step.id } + 1
        val detail = describePlanStep(step)
        AlertDialog.Builder(this)
            .setTitle("تأیید مرحلهٔ $position از ${session.plan.steps.size}")
            .setMessage(
                "$detail\n\nاین اقدام فقط پس از تأیید شما آغاز می‌شود. " +
                    "برای بازکردن وب‌سایت، مقصد در مرورگر Android باز خواهد شد. " +
                    "برای فایل، انتخاب‌گر رسمی Android نمایش داده می‌شود."
            )
            .setNegativeButton("انصراف", null)
            .setPositiveButton("تأیید و ادامه") { _, _ -> runReviewedPlanStep(session, step) }
            .show()
    }

    private fun runReviewedPlanStep(session: AgentPlanExecutionSession, step: AgentPlanStep) {
        val actionId = session.actionIdFor(step.id)
        val decision = AgentPlanExecutionPolicy.evaluate(
            step,
            PlanPolicyContext(
                actionId = actionId,
                appInForeground = activityInForeground,
                ownerConfirmed = true
            )
        )
        if (!decision.allowed) {
            setStatus("سیاست مجوز اجرای این مرحله را نپذیرفت: ${decision.reason}")
            updateExecutionUi()
            return
        }

        runCatching {
            when (step.operation) {
                AgentOperation.OPEN_APP -> {
                    session.markDispatched(step.id)
                    launchAllowlisted(step.target ?: throw IllegalArgumentException("App alias is missing."))
                    setStatus("درخواست بازکردن برنامه به Android سپرده شد؛ پس از بازگشت، نتیجه را بررسی کنید.")
                }
                AgentOperation.OPEN_URL -> {
                    val target = step.target ?: throw IllegalArgumentException("HTTPS destination is missing.")
                    session.markDispatched(step.id)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    setStatus("نشانی به مرورگر Android سپرده شد؛ پس از بازگشت، نتیجه را بررسی کنید.")
                }
                AgentOperation.READ_SELECTED_DOCUMENT -> {
                    session.markWaitingForResource(step.id)
                    pendingDocumentStepId = step.id
                    setStatus("پس از انتخاب فایل، مجوز منبع بررسی و پیش‌نمایش محلی انجام می‌شود.")
                    startActivityForResult(
                        AndroidDocumentAccess.createOpenDocumentIntent(),
                        executionDocumentRequest
                    )
                }
                AgentOperation.SHOW_TEXT -> {
                    plannerOutput.text = "متن تأییدشده برای نمایش:\n\n" + (step.text ?: "")
                    session.markCompleted(step.id, detail = "متن در برنامه نمایش داده شد.")
                    setStatus("مرحلهٔ نمایش متن تکمیل شد.")
                }
            }
        }.onFailure { error ->
            if (pendingDocumentStepId == step.id) pendingDocumentStepId = null
            runCatching { session.markFailed(step.id, error.message ?: "Action failed.") }
            setStatus("اجرای مرحله ناموفق بود؛ برای جلوگیری از اجرای تکراری، ادامهٔ طرح متوقف شد.")
        }
        updateExecutionUi()
    }

    private fun handlePlanDocumentResult(resultCode: Int, data: Intent?) {
        val session = executionSession
        val stepId = pendingDocumentStepId
        pendingDocumentStepId = null
        if (session == null || stepId == null) {
            setStatus("جلسهٔ اجرای فایل دیگر فعال نیست؛ هیچ فایلی خوانده نشد.")
            updateExecutionUi()
            return
        }
        if (resultCode != RESULT_OK) {
            session.markFailed(stepId, "Document selection was cancelled.")
            setStatus("انتخاب فایل لغو شد؛ ادامهٔ طرح متوقف شد.")
            updateExecutionUi()
            return
        }
        val uri = data?.data
        if (uri == null) {
            session.markFailed(stepId, "The document picker returned no resource.")
            setStatus("انتخاب‌گر فایلی برنگرداند؛ ادامهٔ طرح متوقف شد.")
            updateExecutionUi()
            return
        }

        val actionId = session.actionIdFor(stepId)
        val decision = AgentPlanExecutionPolicy.evaluate(
            session.plan.steps.first { it.id == stepId },
            PlanPolicyContext(
                actionId = actionId,
                appInForeground = activityInForeground,
                ownerConfirmed = true,
                resourceSelected = true
            )
        )
        if (!decision.allowed) {
            session.markFailed(stepId, decision.reason)
            setStatus("سیاست مجوز خواندن فایل انتخاب‌شده را نپذیرفت.")
            updateExecutionUi()
            return
        }

        val resultFlags = data?.flags ?: 0
        if (!DocumentAccessPolicy.hasReadPermission(resultFlags)) {
            session.markFailed(stepId, "Android did not grant read access to the selected document.")
            setStatus("Android مجوز خواندن فایل را صادر نکرد؛ هیچ محتوایی خوانده نشد.")
            updateExecutionUi()
            return
        }
        setBusy("در حال بررسی مجوز و خواندن فایل انتخاب‌شده به‌صورت محلی…")
        executor.execute {
            val readResult = runCatching {
                val preview = AndroidDocumentAccess.readTextPreview(contentResolver, uri)
                val persisted = runCatching {
                    AndroidDocumentAccess.persistReadPermission(contentResolver, uri, resultFlags)
                }.getOrDefault(false)
                preview to persisted
            }
            runOnUiThread {
                readResult.onSuccess { (preview, persisted) ->
                    documentPreview.text =
                        "نوع فایل: ${preview.mimeType}\nحجم خوانده‌شده: ${preview.bytesRead} بایت\n" +
                            "دسترسی پایدار: ${if (persisted) "بله" else "خیر؛ فقط دسترسی موقت انتخاب‌گر"}\n\n" +
                            preview.text
                    session.markCompleted(
                        stepId,
                        resourceVerified = true,
                        detail = "فایل انتخاب‌شده فقط روی دستگاه پیش‌نمایش شد."
                    )
                    setStatus("فایل به‌صورت محلی پیش‌نمایش شد و به Cloud ارسال نشد.")
                }.onFailure { error ->
                    session.markFailed(stepId, error.message ?: "Local document preview failed.")
                    documentPreview.text = "پیش‌نمایش در دسترس نیست: ${error.message ?: "خطای نامشخص"}"
                    setStatus("خواندن فایل ناموفق بود؛ ادامهٔ طرح متوقف شد.")
                }
                updateExecutionUi()
            }
        }
    }

    private fun confirmDispatchedStepCompletion() {
        val session = executionSession ?: return
        val record = session.records().firstOrNull { it.state == PlanStepState.DISPATCHED } ?: return
        val step = session.plan.steps.first { it.id == record.stepId }
        AlertDialog.Builder(this)
            .setTitle("تأیید نتیجهٔ اقدام")
            .setMessage(
                "${describePlanStep(step)}\n\nآیا خودتان بررسی کرده‌اید که این مرحله با موفقیت انجام شده است؟ " +
                    "تأیید شما فقط مرحلهٔ فعلی را تکمیل می‌کند."
            )
            .setNegativeButton("هنوز نه", null)
            .setPositiveButton("بله، بررسی کردم") { _, _ ->
                runCatching {
                    session.markCompleted(step.id, ownerVerified = true, detail = "Owner verified after returning to the app.")
                }.onSuccess {
                    setStatus("تکمیل مرحله توسط شما تأیید شد.")
                }.onFailure {
                    setStatus("تأیید مرحله انجام نشد: ${it.message ?: "خطای نامشخص"}")
                }
                updateExecutionUi()
            }
            .show()
    }

    private fun describePlanStep(step: AgentPlanStep): String = when (step.operation) {
        AgentOperation.OPEN_APP -> "بازکردن برنامهٔ مجاز: ${step.target}"
        AgentOperation.OPEN_URL -> "بازکردن نشانی HTTPS:\n${step.target}"
        AgentOperation.READ_SELECTED_DOCUMENT -> "بازکردن انتخاب‌گر رسمی Android برای انتخاب یک فایل متنی"
        AgentOperation.SHOW_TEXT -> "نمایش متن:\n${step.text}"
    }

    private fun updateExecutionUi() {
        if (!::executionStatus.isInitialized) return
        val session = executionSession
        if (session == null) {
            executionStatus.text = plannerFailureMessage ?: "طرحی برای اجرا وجود ندارد."
            executionNextButton.visibility = ViewGroup.GONE
            executionVerifyButton.visibility = ViewGroup.GONE
            return
        }

        val lines = session.records().mapIndexed { index, record ->
            val stateText = when (record.state) {
                PlanStepState.PENDING -> "در انتظار"
                PlanStepState.WAITING_FOR_RESOURCE -> "در انتظار انتخاب منبع"
                PlanStepState.DISPATCHED -> "منتظر تأیید نتیجه توسط شما"
                PlanStepState.COMPLETED -> "تکمیل‌شده"
                PlanStepState.FAILED -> "ناموفق؛ ادامه متوقف است"
            }
            "${index + 1}. ${record.stepId}: $stateText"
        }
        executionStatus.text = lines.joinToString("\n")
        if (session.isComplete()) {
            executionStatus.append("\n\nتمام مراحل با تأیید لازم تکمیل شدند.")
            executionNextButton.visibility = ViewGroup.GONE
            executionVerifyButton.visibility = ViewGroup.GONE
            return
        }

        val dispatched = session.records().any { it.state == PlanStepState.DISPATCHED }
        if (dispatched) {
            executionNextButton.visibility = ViewGroup.GONE
            executionVerifyButton.visibility = ViewGroup.VISIBLE
            executionVerifyButton.isEnabled = activityInForeground
            return
        }
        executionVerifyButton.visibility = ViewGroup.GONE
        val next = session.nextRunnableStep()
        if (next != null && pendingDocumentStepId == null) {
            executionNextButton.text = "تأیید و اجرای مرحلهٔ ${session.plan.steps.indexOfFirst { it.id == next.id } + 1}"
            executionNextButton.visibility = ViewGroup.VISIBLE
            executionNextButton.isEnabled = activityInForeground
        } else {
            executionNextButton.visibility = ViewGroup.GONE
            if (session.records().any { it.state == PlanStepState.FAILED }) {
                executionStatus.append("\n\nبرای جلوگیری از اجرای تکراری، این طرح متوقف شده است.")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == fatherContactRequest) {
            val selectedUri = if (resultCode == RESULT_OK) data?.data else null
            if (selectedUri == null) {
                pendingCallAfterContactSelection = false
                setStatus("انتخاب مخاطب لغو شد؛ تماسی برقرار نشد.")
                return
            }
            val selected = runCatching {
                contentResolver.query(
                    selectedUri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) null else {
                        val name = cursor.getString(0).orEmpty()
                        val number = cursor.getString(1).orEmpty().filter { it.isDigit() || it == '+' }
                        if (number.length !in 3..20) null else name to number
                    }
                }
            }.getOrNull()
            if (selected == null) {
                pendingCallAfterContactSelection = false
                setStatus("شمارهٔ معتبر از مخاطب انتخاب‌شده دریافت نشد.")
                return
            }
            store.saveFatherContact(selected.first, selected.second)
            if (::fatherContactStatus.isInitialized) fatherContactStatus.text = fatherContactSummary()
            val shouldCall = pendingCallAfterContactSelection
            pendingCallAfterContactSelection = false
            setStatus("مخاطب پدر به‌صورت رمزگذاری‌شده ذخیره شد.")
            if (shouldCall) placeFatherCall()
            return
        }
        if (requestCode == executionDocumentRequest) {
            handlePlanDocumentResult(resultCode, data)
            return
        }
        if (requestCode != openDocumentRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: run {
            setStatus("فایلی انتخاب نشد.")
            return
        }
        val resultFlags = data?.flags ?: 0
        setBusy("در حال خواندن فایل انتخاب‌شده به‌صورت محلی…")
        executor.execute {
            val result = runCatching {
                val preview = AndroidDocumentAccess.readTextPreview(contentResolver, uri)
                val persisted = runCatching {
                    AndroidDocumentAccess.persistReadPermission(contentResolver, uri, resultFlags)
                }.getOrDefault(false)
                preview to persisted
            }
            runOnUiThread {
                result.onSuccess { (preview, persisted) ->
                    documentPreview.text = "نوع فایل: ${preview.mimeType}\nحجم خوانده‌شده: ${preview.bytesRead} بایت\nدسترسی پایدار: ${if (persisted) "بله" else "خیر؛ فقط دسترسی موقت انتخاب‌گر"}\n\n${preview.text}"
                    setStatus("پیش‌نمایش فایل فقط روی همین دستگاه آماده شد؛ هیچ محتوایی ارسال نشد.")
                }.onFailure { error ->
                    documentPreview.text = "پیش‌نمایش در دسترس نیست: ${error.message ?: "خطای نامشخص"}"
                    setStatus("خواندن فایل ناموفق بود.")
                }
            }
        }
    }

    private fun toggleVoice() {
        if (voiceActive) {
            voiceController.stop()
            voiceButton.isEnabled = false
            voiceButton.text = "در حال پردازش…"
            setStatus("ضبط متوقف شد؛ نتیجه در حال پردازش محلی است.")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            setStatus("فرمان صوتی کاملاً محلی به Android 12 یا جدیدتر نیاز دارد.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioRequest)
            return
        }
        beginVoice()
    }

    private fun beginVoice() {
        voiceActive = voiceController.start()
        voiceButton.text = if (voiceActive) "توقف فرمان صوتی" else "شروع فرمان صوتی (محلی)"
        if (!voiceActive) setStatus("تشخیص گفتار محلی در دسترس نیست؛ هیچ صدایی به Cloud ارسال نشد.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == callPhoneRequest) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            val shouldCall = pendingCallAfterPermission
            pendingCallAfterPermission = false
            if (granted && shouldCall) placeFatherCall()
            else if (!granted) {
                setStatus("مجوز تماس تلفنی صادر نشد؛ هیچ تماسی برقرار نشد.")
                voiceController.speak("مجوز تماس تلفنی صادر نشد.")
            }
            return
        }
        if (requestCode == recordAudioRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) beginVoice()
            else setStatus("مجوز میکروفون داده نشد؛ قابلیت صوتی غیرفعال ماند.")
        }
    }

    private fun finishVoiceUi() {
        voiceActive = false
        if (::voiceButton.isInitialized) {
            voiceButton.isEnabled = true
            voiceButton.text = "شروع فرمان صوتی (محلی)"
        }
    }

    private fun handleVoiceTranscript(transcript: String) {
        voiceActive = false
        voiceButton.text = "شروع فرمان صوتی (محلی)"
        setStatus("متن تشخیص‌داده‌شده: " + transcript)
        val alias = VoiceIntentResolver.resolve(transcript)
        if (alias == null) {
            val message = "این فرمان در فهرست مجاز نیست؛ هیچ عملیاتی اجرا نشد."
            setStatus(message)
            voiceController.speak(message)
            return
        }
        if (alias == "call_father") {
            runCatching { placeFatherCall() }
                .onFailure {
                    val message = "برقراری تماس ناموفق بود: " + (it.message ?: "خطای نامشخص")
                    setStatus(message)
                    voiceController.speak(message)
                }
            return
        }
        runCatching { launchAllowlisted(alias) }
            .onSuccess {
                val message = if (alias == "chatgpt") "ChatGPT باز شد." else "مرورگر باز شد."
                setStatus(message)
                voiceController.speak(message)
            }
            .onFailure {
                val message = "اجرای فرمان ناموفق بود: " + (it.message ?: "خطای نامشخص")
                setStatus(message)
                voiceController.speak(message)
            }
    }

    private fun chooseFatherContact(forImmediateCall: Boolean = false) {
        pendingCallAfterContactSelection = forImmediateCall
        val picker = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        startActivityForResult(picker, fatherContactRequest)
    }

    private fun fatherContactSummary(): String {
        val name = store.fatherContactName()
        return if (name.isBlank()) {
            "مخاطب پدر هنوز انتخاب نشده است. یک‌بار از فهرست مخاطبین انتخابش کن."
        } else {
            "مخاطب تماس سریع: $name  ·  شماره در حافظهٔ رمزگذاری‌شده نگهداری می‌شود."
        }
    }

    /** Places a call immediately after the explicit voice command and Android permission grant. */
    private fun placeFatherCall() {
        val number = store.fatherContactPhone()
        if (number.isBlank()) {
            setStatus("برای تماس مستقیم، ابتدا مخاطب پدر را انتخاب کن.")
            voiceController.speak("مخاطب پدر هنوز انتخاب نشده. لطفاً یک‌بار آن را انتخاب کن.")
            chooseFatherContact(forImmediateCall = true)
            return
        }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCallAfterPermission = true
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), callPhoneRequest)
            setStatus("برای تماس مستقیم، مجوز تماس Android لازم است.")
            return
        }
        startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null)))
        setStatus("در حال تماس با ${store.fatherContactName().ifBlank { "پدر" }}…")
        voiceController.speak("در حال تماس با پدر.")
    }

    private fun launchAllowlisted(alias: String) {
        when (alias) {
            "chatgpt" -> {
                val appIntent = packageManager.getLaunchIntentForPackage("com.openai.chatgpt")
                if (appIntent != null) startActivity(appIntent)
                else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")))
            }
            "browser" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")))
            else -> throw IllegalArgumentException("Unsupported Android app alias.")
        }
    }

    private fun addFreeProviderKeyEditor(root: LinearLayout, providerId: String, providerLabel: String) {
        val field = EditText(this).apply {
            hint = "کلید API · " + providerLabel
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(145, 170, 158))
            setSingleLine(true)
        }
        root.addView(field, fieldParams())
        root.addView(Button(this).apply {
            text = "ذخیره امن کلید · " + providerLabel
            setOnClickListener {
                runCatching { store.saveProviderApiKey(providerId, field.text.toString()) }
                    .onSuccess {
                        field.text.clear()
                        setStatus("کلید " + providerLabel + " در حافظهٔ رمزگذاری‌شده ذخیره شد.")
                    }
                    .onFailure { setStatus(it.message ?: "ذخیرهٔ کلید ناموفق بود.") }
            }
        }, buttonParams())
        root.addView(Button(this).apply {
            text = "حذف کلید · " + providerLabel
            setOnClickListener {
                runCatching { store.clearProviderApiKey(providerId) }
                    .onSuccess {
                        field.text.clear()
                        setStatus("کلید " + providerLabel + " حذف شد.")
                    }
                    .onFailure { setStatus(it.message ?: "حذف کلید ناموفق بود.") }
            }
        }, buttonParams())
    }

    private fun fieldParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = 16
    }
    private fun buttonParams() = fieldParams()
    private fun setBusy(message: String) = runOnUiThread { status.text = message }
    private fun setStatus(message: String) { status.text = message }

    private fun scheduleHeartbeat() {
        val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "el-agent-heartbeat", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    override fun onResume() {
        super.onResume()
        activityInForeground = true
        if (::fatherContactStatus.isInitialized) fatherContactStatus.text = fatherContactSummary()
        updateExecutionUi()
    }

    override fun onPause() {
        activityInForeground = false
        updateExecutionUi()
        if (voiceActive) {
            voiceController.cancel()
            voiceActive = false
            if (::voiceButton.isInitialized) {
                voiceButton.isEnabled = true
                voiceButton.text = "شروع فرمان صوتی (محلی)"
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::voiceController.isInitialized) voiceController.close()
        executor.shutdown()
        plannerExecutor.shutdown()
        super.onDestroy()
    }
}
