# Keep rules are added only when a concrete reflection/serialization use requires them.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
