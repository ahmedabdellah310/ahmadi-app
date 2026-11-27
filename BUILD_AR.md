# بناء تطبيق «الأَحْمَدِيّ» بنفسك (APK)

## اللي تحتاجه
- كمبيوتر (ويندوز أو ماك أو لينكس) وإنترنت (أول بناء بينزّل حوالي 2 جيجا).
- **Android Studio** (آخر نسخة) من developer.android.com/studio. هو بيجيب معاه JDK وAndroid SDK. لو طلب منك SDK رقم 36 اقبل التنزيل.
- JDK 21 (لو بتبني من الـ Terminal برا Android Studio).

## الخطوات
1. فك الضغط عن الملف. هتلاقي فولدر `ahmadi-source`.
2. اعمل مفتاح توقيع خاص بيك (مرة واحدة بس، احتفظ بالملف وبالباسورد ومتفقدهمش):
   ```
   keytool -genkeypair -v -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
   ```
   حط `my-upload-key.jks` جوه فولدر `ahmadi-source` نفسه.
3. افتح Terminal جوه الفولدر وحدد الباسورد (باسورد المفتاح اللي كتبته):
   - ماك/لينكس: `export STORE_PASSWORD='باسوردك'; export KEY_PASSWORD='باسوردك'`
   - ويندوز PowerShell: `$env:STORE_PASSWORD='باسوردك'; $env:KEY_PASSWORD='باسوردك'`
   (لو سميت الـ alias غير `upload` زود `KEY_ALIAS` بنفس الطريقة.)
4. ابنِ:
   - ماك/لينكس: `./gradlew assembleFdroidRelease`
   - ويندوز: `gradlew.bat assembleFdroidRelease`
   أول مرة بتاخد من 10 لـ 30 دقيقة.
5. الـ APK هتلاقيه في:
   `app/build/outputs/apk/fdroid/release/`
   - `app-fdroid-universal-release.apk` يشتغل على أي موبايل (ده اللي تستخدمه).
   - `app-fdroid-arm64-v8a-release.apk` أصغر، لموبايلات 64 بت (زي Moto G35).
   انقله للموبايل وثبّته.

## مهم
- لو ملقتش مفتاح التوقيع، الـ APK هيطلع **بدون توقيع** (`...-unsigned.apk`) والموبايل مش هيثبته. لازم الخطوة 2 و3.
- **النسخة اللي هتبنيها بمفتاحك هتبقى موقّعة بمفتاح مختلف عن النسخة المثبتة دلوقتي، فمش هتنزل فوقها كتحديث.** لازم تمسح التطبيق الحالي الأول (وده بيمسح بياناته: إعداداتك وتقدمك. اعمل نسخة احتياطية لو في حاجة مهمة) وبعدين تثبّت نسختك.
- موقع التحديثات الإجباري (`ahmadi-updates.vercel.app`) بيقبل بس APK موقّع بنفس مفتاحي، فنسختك مش هتتحدث منه إلا لو رفعت عليه نسخة موقّعة بنفس مفتاحك وإنت صاحب الحساب.
- فولدر `ratl` جزء من المشروع ولازم يفضل جنب `app`.
- مفيش أي مفاتيح أو باسوردات جوه الملف ده.
