# Don't strip/rename the Xposed module entry or its reflectively-called constructor
-keep class com.example.popupblocker.PopupBlockerModule { *; }

# Keep any other module entry classes too, just in case
-keep class * extends io.github.libxposed.api.XposedModule { *; }

-keepattributes *Annotation*

# The API is provided by the framework (compileOnly), silence warnings
-dontwarn io.github.libxposed.api.**
