# PDF Toolbox release rules
# pdfbox-android uses reflection/internals that R8 must not strip
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.commons.logging.**
-dontwarn javax.naming.**
