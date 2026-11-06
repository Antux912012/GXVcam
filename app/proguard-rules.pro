# GXVcam Proguard rules
-keep class org.gxvcam.camera.MtkMotoVendorTags { *; }
-keepclassmembers class * {
    native <methods>;
}
# Reflection in GXVcamApplication exposes hidden aux cameras to CameraX
-keepclassmembers class androidx.camera.camera2.internal.Camera2CameraFactory {
    private final java.util.List mAvailableCameraIds;
}
