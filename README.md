# Ultron APK (v1.1)
1. Create a PRIVATE GitHub repo `ultron-apk`, push this folder (git init; git add .; git commit -m init; git branch -M main; git remote add origin <url>; git push -u origin main).
2. GitHub > Actions > "Build Ultron APK" runs (~4 min). Then Releases > "latest" > app-debug.apk.
3. Install (allow unknown sources). Open Ultron: paste Groq key > Save > Grant permissions (+ Draw over apps) > Battery Unrestricted > Start.
4. NEW - tap "Enable Accessibility" and turn Ultron ON (needed so he can really close apps).
   If it is greyed out: Settings > Apps > Ultron > top-right menu > "Allow restricted settings", then try again.
5. NEW - tap "Install Telugu voice" and download the Telugu voice (Google Text-to-speech).
6. Language button: Telugu (default, also understands English words) or English.

## What's new in v1.1
- Say "Ultron": he stays awake 30 seconds, and again 30 seconds after he finishes talking. The circle stays on screen while he is awake.
- "Ultron, open hologram" opens a hologram page in Chrome. In it: tap the mic and say "generate image of a dragon" (or in Telugu) and the image appears. Free, no key needed (Pollinations).
- "Ultron, close WhatsApp" really closes it (Force stop through Accessibility). "close all apps" presses Clear all. If it cannot, he now says so honestly.
- Telugu: hears Telugu (te-IN), answers in Telugu, speaks with the Telugu voice. Wake word works as "Ultron" or in Telugu letters.
- Default model is now openai/gpt-oss-120b (better Telugu). You can change it in the Model box.

Key stays on the phone only. Needs internet (speech + Groq + image server).
