# Room locates its generated implementation from the database class name.
-keepnames class com.ai.assistance.operit.rpg.data.RpgDatabase
-keep class com.ai.assistance.operit.rpg.data.RpgDatabase_Impl { *; }
-keepnames class com.ai.assistance.operit.rpg.agent.data.AgentDatabase
-keep class com.ai.assistance.operit.rpg.agent.data.AgentDatabase_Impl { *; }

# Serialization consumer rules cover normal generated serializers. Keep saved-model entry points.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.ai.assistance.operit.rpg.**$$serializer { *; }
-keepclassmembers class com.ai.assistance.operit.rpg.** { *** Companion; }
