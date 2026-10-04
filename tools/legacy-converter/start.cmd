@echo off
setlocal
cd /d "%~dp0"
where py.exe >nul 2>nul
if not errorlevel 1 (
    py -3 -c "import sys, tkinter; assert sys.version_info >= (3, 8)" >nul 2>nul
    if not errorlevel 1 goto launch_py
)
where python.exe >nul 2>nul
if errorlevel 1 goto missing_python
python -c "import sys, tkinter; assert sys.version_info >= (3, 8)" >nul 2>nul
if errorlevel 1 goto missing_python
python -m legacy_converter --gui
goto done

:launch_py
py -3 -m legacy_converter --gui
goto done

:missing_python
echo Please install Python 3.8+ with Tcl/Tk, then run this launcher again.
pause
exit /b 1

:done
if errorlevel 1 pause
