# Free the COM port from a wedged `scripts/serial_capture.py` run: a capture that
# outlives the unit's reboot keeps the port open, and the next capture then fails
# to open it. Matches the capture script by file name only, and only python-ish
# process names, so an unrelated interpreter is never touched.
#   powershell -NoProfile -File scripts/kill_serial_capture.ps1
Get-CimInstance Win32_Process -Filter "Name LIKE 'python%.exe'" |
  Where-Object { $_.CommandLine -like '*serial_capture.py*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force; "killed $($_.ProcessId)" }
