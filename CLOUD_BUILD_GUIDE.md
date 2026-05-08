# 📦 How to Build KidTime APK in the Cloud (No Software to Install!)

This guide walks you through building your Android TV app using **GitHub Actions** — a free service that builds the app for you on Google's/Microsoft's servers. You only need a web browser.

---

## 🎯 What you'll do (overview)

1. Create a free GitHub account (2 min)
2. Upload the project folder (2 min)
3. Wait while GitHub builds it (3-5 min)
4. Download the finished APK (1 min)
5. Install on your TV (5 min)

**Total time: ~15 minutes**, and you never install anything on your computer.

---

## STEP 1: Create a GitHub account

1. Go to **https://github.com/signup**
2. Sign up with email + password (free — pick the "Free" plan)
3. Verify your email when GitHub asks

---

## STEP 2: Create a new repository

1. After login, click the green **"New"** button (top-left, or go to https://github.com/new)
2. Fill in:
   - **Repository name:** `KidTimeTV` (anything you like)
   - **Description:** Optional
   - Choose **Public** or **Private** (Private is fine — both build for free)
   - ⚠️ **DO NOT** check "Add a README" or "Add .gitignore" — we already have those
3. Click **"Create repository"**

You'll land on a page that says "Quick setup".

---

## STEP 3: Upload the project files

GitHub gives you two ways. The easiest is **drag-and-drop in the browser**:

1. On the repository page (the empty one you just made), click the link **"uploading an existing file"** (it's in the middle of the page near the green code button)
2. **Unzip** the `KidTimeTV` folder on your computer first (so you have a regular folder, not a ZIP)
3. **Drag the contents** of the unzipped folder into the GitHub upload area
   - Important: drag the **contents** (the `app` folder, `build.gradle`, etc.) — **NOT** the outer `KidTimeTV` folder itself
   - On Windows: open the folder, select all files (Ctrl+A), drag them in
   - On Mac: open the folder, select all (Cmd+A), drag them in
4. Wait for the upload (usually 30-60 seconds)
5. Scroll down, type a commit message like `Initial upload`, click **"Commit changes"**

✅ You should now see all the project files listed in your repository.

---

## STEP 4: Trigger the build

Now we tell GitHub to build the APK.

1. Click the **"Actions"** tab at the top of your repository
2. If GitHub asks "Get started with GitHub Actions" — click **"I understand my workflows, go ahead and enable them"**
3. You should see a workflow called **"Build APK"** in the left sidebar
4. Click on it
5. Click the **"Run workflow"** button (right side) → click the green **"Run workflow"** button in the dropdown
6. Wait ~30 seconds, then refresh the page

You'll see a yellow circle (running) → it turns green ✅ when done. **It usually takes 3-5 minutes** the first time.

---

## STEP 5: Download the APK

1. Once the build is green ✅, click on the workflow run (the row in the list)
2. Scroll to the bottom — you'll see a section called **"Artifacts"**
3. Click **"KidTime-debug-apk"** to download a ZIP file
4. Unzip it on your computer — inside is `app-debug.apk` 🎉

That's your installable Android app!

---

## STEP 6: Install on your Android TV

You have two ways:

### Method A: USB stick (simplest)

1. Copy `app-debug.apk` to a USB stick
2. Plug the USB stick into your TV
3. On your TV, install a free file manager from Play Store, e.g. **"X-plore File Manager"** or **"FX File Explorer"**
4. Open the file manager → browse to the USB drive → tap `app-debug.apk`
5. TV will say "Install from unknown sources is blocked"
   - Tap **Settings** → enable installation for that file manager
6. Go back, tap the APK again → **Install**
7. Done! KidTime now appears in your TV's apps row.

### Method B: ADB over Wi-Fi (more technical, no USB needed)

If you have a phone, install **"Send Files to TV"** app on both your phone and TV — then send the APK from phone to TV and tap it to install.

OR if you have ADB on your computer:
```bash
adb connect <TV_IP_ADDRESS>:5555
adb install app-debug.apk
```

---

## STEP 7: First-time setup on the TV

Open KidTime from your TV's apps row. You'll see the parent control panel.

1. **Grant Usage Access** → tap that button → find KidTime in the list → toggle ON
2. **Grant Overlay Permission** → tap that button → toggle "Display over other apps" ON
3. **Choose apps to lock** → check YouTube, Netflix, Disney+, etc.
4. **Edit kids & settings** → set names, PINs, and daily limit (default 3 hours)
5. **Test Lock Screen** → see how the PIN prompt looks

You're done! Now whenever a kid opens YouTube/Netflix/etc., the lock screen will appear asking for their PIN.

---

## 🔄 Want to make changes later?

If you (or I) update the code, you don't need to redo everything:

1. In your GitHub repo, click on the file you want to change
2. Click the pencil icon ✏️
3. Edit, then click "Commit changes"
4. **The build runs automatically** — go to Actions tab and download the new APK in 3-5 min

---

## 🐛 Troubleshooting

**"Workflows aren't running"**
→ Go to the Actions tab → click the green "I understand..." button to enable workflows.

**"Build failed with red ❌"**
→ Click on the failed run → click the failed step to see the error. Most common: missing files. Re-upload making sure you uploaded the **contents** of the folder, not the folder itself.

**"My TV won't install the APK"**
→ Make sure "Install unknown apps" is enabled for whatever file manager you used. On Google TV: Settings → Apps → Security & restrictions → Unknown sources.

**"The APK installs but app keeps closing"**
→ Make sure the TV is running Android 7.0 or newer (most TVs since ~2018 do).

**"Lock screen doesn't pop up when YouTube opens"**
→ You haven't granted Usage Access AND Overlay permissions yet. Both are required.

---

## 💡 Tips

- **Keep your repo Private** if you don't want others seeing it (still free, still builds).
- **Bookmark your Actions page** — clicking "Run workflow" anytime gives you a fresh APK.
- The free tier of GitHub Actions gives you **2,000 build minutes/month** — way more than you'll ever need.

---

Made with ❤️ for parents who want their kids to enjoy TV in a balanced way.
