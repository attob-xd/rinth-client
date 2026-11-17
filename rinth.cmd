@echo off
rem The Rinth build CLI on Windows. Same as ./rinth: builds the tool with Gradle, then runs it.
setlocal
set "ROOT=%~dp0"
set "MODULE=build"
if "%~1"=="survey" set "MODULE=survey"
if "%~1"=="copycheck" set "MODULE=copycheck"

call "%ROOT%gradlew.bat" --quiet --console=plain -p "%ROOT%." ":tools:%MODULE%:installDist" || exit /b 1

set "RINTH_BUILD_OPTS=%RINTH_BUILD_OPTS% -Drinth.cwd=%CD%"
set "RINTH_SURVEY_OPTS=%RINTH_SURVEY_OPTS% -Drinth.cwd=%CD%"
set "RINTH_COPYCHECK_OPTS=%RINTH_COPYCHECK_OPTS% -Drinth.cwd=%CD%"

rem The survey and copycheck take their own options; the command name isn't one of them.
if "%MODULE%"=="survey" (
  call "%ROOT%tools\survey\build\install\rinth-survey\bin\rinth-survey.bat" %2 %3 %4
) else if "%MODULE%"=="copycheck" (
  call "%ROOT%tools\copycheck\build\install\rinth-copycheck\bin\rinth-copycheck.bat" %2 %3 %4
) else (
  call "%ROOT%tools\build\build\install\rinth-build\bin\rinth-build.bat" %*
)
exit /b %ERRORLEVEL%
