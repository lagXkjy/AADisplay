# Covers UI base classes (BaseActivity lateinit baseBinding private set must survive R8).
-keep class io.github.nitsuya.aa.display.** { *; }
# BouncyCastle Ed25519/X25519 used by AirPlay pair-setup/verify.
-keep class org.bouncycastle.crypto.params.Ed25519** { *; }
-keep class org.bouncycastle.crypto.params.X25519** { *; }
-keep class org.bouncycastle.crypto.signers.Ed25519Signer { *; }

# AAP (AARE + aap_protobuf): protobuf-javalite resolves schema fields by name
# (e.g. instanceId_). R8 renaming → "Field instanceId_ for … not found" right after TLS.
# allowshrinking: channel dispatch is by message id and unknown ids are ignored,
# so unreferenced generated messages (radio/mediabrowser/…) may be removed.
-keep,allowshrinking class aap_protobuf.** { *; }
-keep class net.prototux.aare.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**