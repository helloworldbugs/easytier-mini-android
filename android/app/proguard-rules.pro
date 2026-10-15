# Framework-only app: every class used at runtime is either referenced from
# AndroidManifest.xml (AGP keeps those automatically) or from framework code.
# Nothing app-specific needs extra keep rules.

# org.json is part of android.jar; just silence the library-path warning if any.
-dontwarn org.json.**
