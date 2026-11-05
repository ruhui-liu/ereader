# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JavaScript bridge used by the reader WebView.
-keepclassmembers class com.ruhuiliu.ereader.reader.ReaderBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# The Anthropic Java SDK ships its own R8 rules (META-INF/proguard/anthropic-java-core.pro);
# these only silence warnings about optional dependencies that are not used on Android.
-dontwarn com.github.victools.**
-dontwarn io.swagger.**
-dontwarn io.opentelemetry.**
-dontwarn com.standardwebhooks.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# Dropbox SDK (no reflection on its own classes; warnings for server-side optional deps).
-dontwarn com.dropbox.core.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.google.appengine.**
-dontwarn javax.servlet.**
