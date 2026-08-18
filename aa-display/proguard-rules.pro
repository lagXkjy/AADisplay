-keep class io.github.nitsuya.aa.display.** { *; }
# ViewBinding bases live outside the keep above; R8 rename + private-set lateinit
# breaks subclass `::baseBinding.isInitialized` (IllegalAccessError → AA crash-loop exit).
-keep class io.github.duzhaokun123.template.bases.** { *; }