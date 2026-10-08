@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   Hex Casting NBT Angles Converter
echo   0=w  1=e  2=d  3=s  4=a  5=q
echo ============================================
echo.

:loop
set "input="
set /p "input=Paste angles:[B;...] or /setblock line (blank to quit): "
if "!input!"=="" goto :end

rem Strip prefix and suffix
set "input=!input:angles:[B;=!"
set "input=!input:B;=!"
set "input=!input:]=!"
set "input=!input:,= !"
set "input=!input:  = !"

set "output="
set "cnt=0"
for %%a in (!input!) do (
    set "val=%%a"
    set "val=!val:B=!"
    if "!val!"=="0" set "output=!output!w"
    if "!val!"=="1" set "output=!output!e"
    if "!val!"=="2" set "output=!output!d"
    if "!val!"=="3" set "output=!output!s"
    if "!val!"=="4" set "output=!output!a"
    if "!val!"=="5" set "output=!output!q"
    set /a cnt+=1
)

echo.
echo Count: !cnt!
echo Result: !output!
echo.
goto :loop

:end
echo Bye.
endlocal