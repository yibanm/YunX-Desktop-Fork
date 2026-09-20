; ============================================================
;  云析 YunX-Desktop-fork  —  NSIS 安装脚本（Unicode，按用户安装，免 UAC）
;  在 Linux 上用 makensis 交叉编译：
;    NSISDIR=<nsis data dir> makensis -DAPPFILES=<app 目录> -DICON=<ico> setup.nsi
; ============================================================
Unicode true
SetCompressor /SOLID lzma

!ifndef APPFILES
  !define APPFILES "..\..\release\YunX-Desktop-Fork"
!endif
!ifndef ICON
  !define ICON "..\..\YunX-Desktop.ico"
!endif
!ifndef OUTFILE
  !define OUTFILE "..\..\release\YunX-Desktop-Fork-1.1.7-Setup.exe"
!endif

!define APPNAME    "云析 YunX-Desktop-fork"
!define SHORTNAME  "YunX"
!define EXENAME    "YunX-Desktop-Fork.exe"
!define AUMID      "YunX-Desktop-fork"
!define APPVERSION "1.1.7"

Name "${APPNAME}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\YunX-Desktop-Fork"
InstallDirRegKey HKCU "Software\YunX-Desktop-Fork" "InstallDir"
RequestExecutionLevel user
ShowInstDetails show
ShowUnInstDetails show

; 安装包/卸载器图标
Icon "${ICON}"
UninstallIcon "${ICON}"

!include "MUI2.nsh"

!define MUI_ICON "${ICON}"
!define MUI_UNICON "${ICON}"
!define MUI_ABORTWARNING

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\${EXENAME}"
!define MUI_FINISHPAGE_RUN_TEXT "立即启动云析"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

; ------------------------------------------------------------
;  安装
; ------------------------------------------------------------
Section "Install"
  SetOutPath "$INSTDIR"
  File /r "${APPFILES}\*.*"

  ; 记录安装目录
  WriteRegStr HKCU "Software\YunX-Desktop-Fork" "InstallDir" "$INSTDIR"

  ; 卸载器
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  ; —— 基础快捷方式（不依赖 PowerShell，作为兜底；随后被 PS1 覆盖并写入 AUMID）——
  CreateDirectory "$SMPROGRAMS\${SHORTNAME}"
  CreateShortcut "$SMPROGRAMS\${SHORTNAME}\${SHORTNAME}.lnk" "$INSTDIR\${EXENAME}" "" "$INSTDIR\${EXENAME}" 0
  CreateShortcut "$DESKTOP\${SHORTNAME}.lnk" "$INSTDIR\${EXENAME}" "" "$INSTDIR\${EXENAME}" 0

  ; —— 用 PowerShell 重建快捷方式并写入 AppUserModelID（任务栏可固定、右键菜单完整）——
  SetOutPath "$PLUGINSDIR"
  File "CreateShortcuts.ps1"
  nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -File "$PLUGINSDIR\CreateShortcuts.ps1" -Target "$INSTDIR\${EXENAME}" -WorkDir "$INSTDIR" -Aumid "${AUMID}" -Name "${SHORTNAME}"'
  Pop $0
  SetOutPath "$INSTDIR"

  ; 开始菜单卸载项
  CreateShortcut "$SMPROGRAMS\${SHORTNAME}\卸载云析.lnk" "$INSTDIR\Uninstall.exe" "" "$INSTDIR\Uninstall.exe" 0

  ; 添加/删除程序
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "DisplayName" "${APPNAME}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "DisplayIcon" '"$INSTDIR\${EXENAME}",0'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "DisplayVersion" "${APPVERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "Publisher" "YunX-Desktop-fork"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork" "NoRepair" 1
SectionEnd

; ------------------------------------------------------------
;  卸载
; ------------------------------------------------------------
Section "Uninstall"
  ; 删快捷方式
  Delete "$DESKTOP\${SHORTNAME}.lnk"
  RMDir /r "$SMPROGRAMS\${SHORTNAME}"

  ; 删程序文件（保留用户数据 ~/.yunx-pc 与 文档/YunX-Desktop 备份）
  RMDir /r "$INSTDIR"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\YunX-Desktop-Fork"
  DeleteRegKey HKCU "Software\YunX-Desktop-Fork"
SectionEnd
