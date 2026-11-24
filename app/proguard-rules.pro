# Providers are loaded by class name from assets/model_providers.json.
-keep public class * implements com.example.yapit.provider.ModelProvider { public <init>(); }
-keepclassmembers class com.example.yapit.provider.PocketTtsProvider$GenerationCallback {
    public java.lang.Integer invoke(float[]);
}
