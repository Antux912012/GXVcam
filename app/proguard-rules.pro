# GXVcam Proguard rules
-keep class org.gxvcam.camera.MtkMotoVendorTags { *; }
-keepclassmembers class * {
    native <methods>;
}
