# ATOMOS Remote Backend local hardware build

This build has a separate Android application ID:

`com.atomosremote.nativeapp.backend`

The baseline app can remain installed as `com.atomosremote.nativeapp`.

The backend-enabled app reuses the existing `AtomosViewModel.gatt` instance. No second GattClient is created.

## Run

```powershell
cd E:\AtomosRemoteWithBackend
Set-ExecutionPolicy -Scope Process Bypass -Force
.\VERIFY-BACKEND-APP-WIRING.ps1
.\RUN-BACKEND-HARDWARE-LOCAL.ps1
```

The runner:

1. typechecks/builds local backend
2. starts backend if needed
3. optionally starts simulator with `-StartSimulator`
4. sets `adb reverse tcp:8787 tcp:8787`
5. builds Android debug APK
6. installs separate backend app
7. launches it
8. opens filtered live logcat capture
9. waits for `ag2-main` to appear at `/api/v1/devices`
10. prints APK and log paths

After `ag2-main` registration, pair the recorder using the unchanged TCS flow and run `TEST-LOCAL-ALL-TABS.ps1`.
