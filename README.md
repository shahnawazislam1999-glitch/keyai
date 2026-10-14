# Sentence Buddy

Type a sentence in WhatsApp (or any app). Pause for a moment and Sentence Buddy shows 3–6 better English
alternatives above your keyboard, written by Google Gemini (free tier). Tap one and it replaces the text in the box.

* A tiny, faint (10% opacity) floating bubble that you can drag anywhere
* Works with your normal keyboard (Gboard etc.), no new keyboard needed
* Tones: Natural, Professional, Friendly, Polite, Short & simple
* Understands Hindi/Hinglish and converts it into English
* Password, e-mail and URL fields are never read

## Build the APK with GitHub (no Android Studio needed)

1. Create a new GitHub repository and upload **all files of this folder** (keep the `.github` folder).
2. Open the **Actions** tab, choose **Build APK** and click **Run workflow** (it also runs on every push).
3. When it finishes, open the run and download the artifact **SentenceBuddy-debug-apk**. Unzip it to get `app-debug.apk`.
4. Copy the APK to your phone and install it (allow "Install unknown apps" when asked).

You can also open the folder in Android Studio and press Run.

## First-time setup on the phone

1. Open **Sentence Buddy**, paste a free key from <https://aistudio.google.com/apikey> and tap **Save**, then **Test**.
   Testing with "Tell me where is the Mango" should show a list of sentences.
2. Tap **Enable** and switch on **Sentence Buddy** in Accessibility settings.
   * Android 13+ and the switch is greyed out? Open **App info** → ⋮ menu → **Allow restricted settings**, then retry.
3. Open WhatsApp, type a sentence and pause. Suggestions appear above the keyboard.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Send your sentence to the Gemini API |
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`) | Read the text box you type in and write the chosen sentence back |

"Display over other apps" is **not** needed: the bubble and panel are drawn as accessibility overlays.

## Project layout

```
app/src/main/java/com/sentencebuddy/app/
  MainActivity.kt                    Settings and setup screen
  ai/GeminiClient.kt                 Gemini REST client (no extra libraries)
  ai/SuggestionRepository.kt         Settings lookup + cache
  data/AppPreferences.kt, Tone.kt    Settings and tones
  service/SuggestionAccessibilityService.kt   Watches typing, applies the chosen sentence
  service/OverlayController.kt       Floating bubble + suggestion panel
  ui/MaxHeightScrollView.kt          Scroll area for long suggestion lists
```

## Customising

* **Model**: change it in the app (default `gemini-2.5-flash-lite`). Any Gemini model that supports `generateContent` works.
* **Bubble**: opacity (5–100%, default 10%), size and pause time are sliders in the app.
* **Auto press Send**: optional beta switch; it taps WhatsApp's Send button after you choose a sentence.

## Privacy

Your text leaves the phone only when suggestions are requested, and goes straight to Google's Gemini API with your own key.
On the free tier Google may use prompts to improve its products, so avoid typing secrets while the service is on.
The API key is stored only on the device.
