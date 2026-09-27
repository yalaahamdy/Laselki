@echo off
chcp 65001 >nul
setlocal
title Laselki - Windows Builder

echo ============================================
echo   لاسِلكي — بناء تطبيق ويندوز
echo ============================================
echo.

REM تأكد أننا في مجلد desktop-windows ثم اذهب لجذر المشروع
cd /d "%~dp0"

echo [1/6] تثبيت اعتماديات المشروع الرئيسي (قد يستغرق دقائق أول مرة)...
cd /d "%~dp0.."
call npm install
if errorlevel 1 goto :err

echo.
echo [2/6] بناء واجهة الويب Next.js (وضع standalone)...
call npx next build
if errorlevel 1 goto :err

echo.
echo [3/6] نسخ ملفات الخادم إلى مجلد تطبيق ويندوز...
if not exist "desktop-windows\server" mkdir "desktop-windows\server"
xcopy /E /I /Y ".next\standalone" "desktop-windows\server" >nul
if errorlevel 1 goto :err
xcopy /E /I /Y ".next\static" "desktop-windows\server\.next\static" >nul
if errorlevel 1 goto :err
xcopy /E /I /Y "public" "desktop-windows\server\public" >nul
if errorlevel 1 goto :err

echo.
echo [4/6] تثبيت اعتماديات تطبيق ويندوز (Electron)...
cd /d "%~dp0"
call npm install
if errorlevel 1 goto :err

echo.
echo [5/6] تجميع خدمة الإشارة...
call npm run bundle:signaling
if errorlevel 1 goto :err

echo.
echo [6/6] بناء ملفات التثبيت (NSIS + Portable)...
call npm run dist
if errorlevel 1 goto :err

echo.
echo ============================================
echo   تم بنجاح!
echo   ملفات التثبيت في: desktop-windows\dist
echo   - لاسِلكي Setup.exe  (مثبّت)
echo   - لاسِلكي-Portable.exe (نسخة محمولة)
echo.
echo   تجربة سريعة قبل التثبيت:
echo     cd desktop-windows ^&^& npm start
echo ============================================
pause
exit /b 0

:err
echo.
echo ✗ فشلت خطوة من خطوات البناء — راجع الرسالة أعلاه.
pause
exit /b 1
