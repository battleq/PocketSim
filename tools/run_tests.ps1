$env:JAVA_HOME = "C:\Users\a\.local\share\jdk17"
$env:Path = (($env:Path -split ';') | Where-Object { $_ -notmatch '\?' }) -join ';'
$env:GIT_PAGER = "cat"
Set-Location "C:\Users\a\ps_test_link\android"
& "C:\Users\a\.local\share\gradle-8.14\bin\gradle.bat" :app:testDebugUnitTest --console=plain *> "C:\Users\a\ps_test_link\test_final.log"
"DONE exit=$LASTEXITCODE" | Out-File -Append -Encoding utf8 "C:\Users\a\ps_test_link\test_final.status"
