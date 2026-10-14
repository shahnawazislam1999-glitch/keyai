# Sentence Buddy ProGuard / R8 rules.
# org.json and HttpURLConnection are part of the Android framework, so nothing special is needed.
-keep class com.sentencebuddy.app.service.SuggestionAccessibilityService { *; }
