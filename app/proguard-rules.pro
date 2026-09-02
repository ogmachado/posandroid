# Add project specific ProGuard rules here.

# android-pos-licensing task 6.2: precautionary keep for the licensing package.
# Crypto uses JCA string lookups and org.json parsing is manual field-by-field
# (no reflective binding), so this isn't strictly required for today's code —
# it guards a future reflective refactor and enum valueOf() lookups. See
# design.md "R8 / ProGuard".
-keep class com.idos.pos.licensing.** { *; }

# android-pos-licensing task 6.3 (release+minified smoke pass): every ViewModel
# in the app is constructed via reflection in Locals.kt's posViewModel() —
# modelClass.getConstructor(AppContainer::class.java).newInstance(container) —
# so R8 must keep that exact constructor or the app crashes with
# NoSuchMethodException on the first ViewModel it can't resolve reflectively
# (found live: AuthGateViewModel after the activation gate flip).
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(com.idos.pos.core.di.AppContainer);
}
