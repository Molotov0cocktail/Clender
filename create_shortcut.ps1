$TargetFile = "d:\Clender\dist\Clender.exe"
$ShortcutFile = [System.IO.Path]::Combine([Environment]::GetFolderPath("Desktop"), "Clender.lnk")
$WScriptShell = New-Object -ComObject WScript.Shell
$Shortcut = $WScriptShell.CreateShortcut($ShortcutFile)
$Shortcut.TargetPath = $TargetFile
$Shortcut.WorkingDirectory = "d:\Clender\dist"
$Shortcut.Description = "Clender - Smart Schedule Manager"
$Shortcut.Save()
Write-Host "Shortcut created at: $ShortcutFile"