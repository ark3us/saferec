# R8 rules for the release build. The libraries bring their own: google-api-client's model
# classes are filled by reflection on their @Key fields (rules shipped by play-services-auth),
# WorkManager creates workers by class name, Room and androidx.startup are covered too.
# Verified on a phone with the r8test build: sign-in, upload, TSA, recordings list, outbox job.

# Readable crash stack traces: decode them with the build's mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The source is public, so renaming the app's own classes hides nothing. It would turn the
# log tags (Class.getSimpleName()) and our stack frames into single letters. Unused code is
# still removed and optimized.
-keepnames class net.ark3us.saferec.** { *; }
