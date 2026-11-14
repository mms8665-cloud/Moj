package ir.moj.radio.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.moj.radio.ui.theme.MojCyan
import ir.moj.radio.ui.theme.MojNavyCard
import ir.moj.radio.ui.theme.MojNavyDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(onBack: () -> Unit) {
    val sections = listOf(
        "۱. معرفی برنامه و عملکرد آفلاین" to "موج (MOJ) یک بی‌سیم گروهی، پیام‌رسان و ابزار اشتراک‌گذاری فایل و دوربین زنده است که بدون نیاز به اینترنت و فقط از طریق شبکه محلی Wi-Fi یا هات‌اسپات گوشی‌ها کار می‌کند.",
        "۲. انتخاب نام مستعار" to "در اولین ورود، نام مستعار ۲ تا ۲۰ کاراکتری خود را وارد کنید. این نام در پیام‌ها و فهرست اعضا نمایش داده می‌شود و هر زمان از بخش تنظیمات قابل تغییر است.",
        "۳. ایجاد شبکه توسط گوشی مرکزی" to "ابتدا هات‌اسپات گوشی اول را روشن کنید، سپس در صفحه اصلی دکمه «ایجاد شبکه» را بزنید. برنامه یک کد ۸ کاراکتری امن می‌سازد و گوشی شما مدیر شبکه می‌شود.",
        "۴. اتصال با کد دستی" to "گوشی‌های دیگر به هات‌اسپات گوشی مرکزی وصل شوند، دکمه «اتصال با کد» را بزنند و کد ۸ کاراکتری (و در صورت نیاز IP و پورت) را وارد کنند.",
        "۵. تأیید و رد اعضا" to "کد اتصال به‌تنهایی مجوز ورود نیست. درخواست عضو جدید در بخش «اعضا» برای مدیر نمایش داده می‌شود و تا زمانی که مدیر تأیید نکند، امکان ارسال یا دریافت وجود ندارد.",
        "۶. استفاده از بی‌سیم (Push To Talk)" to "دکمه بزرگ آبی پایین صفحه را نگه دارید و صحبت کنید؛ با رها کردن آن، ارسال متوقف و بوق کوتاهی پخش می‌شود. در هر لحظه یک نفر نوبت صحبت دارد تا تداخل صوتی ایجاد نشود.",
        "۷. ارسال پیام متنی" to "پیام خود را در کادر پایین صفحه بنویسید و دکمه ارسال را بزنید. تمام پیام‌ها با رمزنگاری AES-256-GCM در شبکه محلی منتقل می‌شوند.",
        "۸. ارسال عکس از گالری" to "آیکن گالری کنار کادر پیام را بزنید و تصویر موردنظر را انتخاب کنید تا برای اعضای مجاز ارسال شود.",
        "۹. گرفتن عکس با دوربین" to "آیکن دوربین عکاسی را بزنید، عکس بگیرید و بلافاصله در شبکه محلی ارسال کنید.",
        "۱۰. ارسال فایل تا ۱۵۰ مگابایت" to "آیکن سنجاق را بزنید و فایل را انتخاب کنید. فایل‌ها به‌صورت تکه‌ای (Chunked) ارسال می‌شوند، درصد پیشرفت نمایش داده می‌شود و امکان لغو انتقال وجود دارد.",
        "۱۱. اشتراک‌گذاری و مشاهده دوربین زنده" to "دوربین به صورت پیش‌فرض خاموش است. هر کاربر می‌تواند با آیکن دوربین ویدیویی، دوربین خود را فعال کند. سایر اعضا از فهرست اعضا درخواست مشاهده می‌فرستند و فقط با اجازه صاحب گوشی تصویر زنده (حتی چند دوربین هم‌زمان) نمایش داده می‌شود.",
        "۱۲. مشاهده اعضای شبکه" to "با لمس آیکن گروه در نوار بالا، فهرست اعضای آنلاین، وضعیت دوربین آن‌ها و (برای مدیر) دکمه‌های تأیید، رد یا حذف اعضا نمایش داده می‌شود.",
        "۱۳. قطع اتصال و خروج" to "برای خروج کامل از شبکه و توقف سرویس پس‌زمینه، به تنظیمات بروید و دکمه «خروج از شبکه» را بزنید.",
        "۱۴. محدودیت‌های هات‌اسپات و Wi-Fi" to "اتصال زنجیره‌ای چندمرحله‌ای (گوشی دوم هم‌زمان به گوشی اول وصل باشد و برای گوشی سوم هات‌اسپات بدهد) فقط در گوشی‌هایی با پشتیبانی سخت‌افزاری STA/AP Concurrency ممکن است. وضعیت گوشی شما در تنظیمات و فهرست اعضا نمایش داده می‌شود.",
        "۱۵. رفع مشکلات اتصال" to "اگر شبکه به‌صورت خودکار کشف نشد، آدرس IP گوشی مرکزی (معمولاً 192.168.43.1) و پورت 45455 را دستی وارد کنید. برای پایداری در پس‌زمینه (به‌ویژه در گوشی‌های سامسونگ)، بهینه‌سازی باتری را برای موج غیرفعال کنید."
    )

    Scaffold(
        containerColor = MojNavyDark,
        topBar = {
            TopAppBar(
                title = { Text("راهنمای استفاده از موج") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            itemsIndexed(sections) { _, (title, body) ->
                Card(colors = CardDefaults.cardColors(containerColor = MojNavyCard)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(title, color = MojCyan, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(body, fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}
