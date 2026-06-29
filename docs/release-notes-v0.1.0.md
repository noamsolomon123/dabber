דַּבֵּר — הקלדה קולית בעברית, חינמית ו‑100% מקומית (ללא שרת, ללא מנוי, ללא מעקב).

המנוע: whisper.cpp עם המודל **ivrit‑ai/whisper‑large‑v3‑turbo** בקוונטיזציית q5_0. בבדיקה על דאטה עברי (Google FLEURS, 40 קטעים) המודל הזה יצא **הכי מדויק והכי מהיר** מבין שישה מודלים שנבחנו (WER 0.221), ומנצח בפער את Whisper הרגיל בעברית.

## התקנה (אנדרואיד)
1. הורידו את `dabber-v0.1.0.apk` והתקינו (יש לאשר "התקנה ממקורות לא ידועים").
2. פתחו את האפליקציה והקישו **"הורד מודל עברית (548MB)"** — המודל יורד פעם אחת ונשמר במכשיר.
3. אשרו שלוש הרשאות: **מיקרופון**, **ציור מעל אפליקציות**, ו**שירות נגישות** (להזנת הטקסט לשדה הפעיל).
4. הקישו על הבועה הצפה בכל אפליקציה, דברו — והטקסט יוקלד אוטומטית לשדה הפעיל.

## קבצים
- **`dabber-v0.1.0.apk`** — האפליקציה (כולל arm64 לטלפון + x86_64 לאמולטור).
- **`dabber-he.bin`** — מודל העברית (q5_0, ‎~548MB). יורד אוטומטית מתוך האפליקציה, או אפשר לדחוף ידנית ל‑`Android/data/com.dabber/files/models/`.

---

100% on‑device Hebrew voice dictation for Android. Engine: whisper.cpp + ivrit‑ai/whisper‑large‑v3‑turbo (q5_0). Floating mic bubble inserts transcribed text into any app via an accessibility service. No cloud, no account.
