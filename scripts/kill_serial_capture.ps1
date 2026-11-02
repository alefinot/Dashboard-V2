Get-CimInstance Win32_Process -Filter "Name = 'python.exe'" |
  Where-Object { $_.CommandLine -like '*serial_capture*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "killed $($_.ProcessId)" }
