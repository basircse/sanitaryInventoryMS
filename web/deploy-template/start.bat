@echo off
rem Starts the Ajwa Sanitary web app (Windows). Needs Java 21.
cd /d "%~dp0"
java -Duser.timezone=Asia/Dhaka -jar ajwa-web.jar %*
