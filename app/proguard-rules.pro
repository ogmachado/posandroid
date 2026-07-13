# Add project specific ProGuard rules here.

# android-pos-licensing task 6.2: precautionary keep for the licensing package.
# Crypto uses JCA string lookups and org.json parsing is manual field-by-field
# (no reflective binding), so this isn't strictly required for today's code —
# it guards a future reflective refactor and enum valueOf() lookups. See
# design.md "R8 / ProGuard".
-keep class com.idos.pos.licensing.** { *; }
