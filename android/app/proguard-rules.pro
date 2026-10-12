# SQLCipher uses JNI callbacks into these classes.
-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }

# BouncyCastle: only the lightweight Ed25519/X25519/HKDF classes are used; let R8 shrink the rest.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# Make reverse engineering of license checks harder.
-repackageclasses 'a'
-allowaccessmodification
