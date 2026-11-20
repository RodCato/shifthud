import subprocess,pathlib,os,argparse,tempfile,re
parser=argparse.ArgumentParser(description="Destructive test of ONLY the isolated migrationaudit emulator package")
parser.add_argument("--serial",default="emulator-5554")
parser.add_argument("--sdk",default=os.environ.get("ANDROID_HOME",str(pathlib.Path.home()/"Library/Android/sdk")))
parser.add_argument("--output",default=None)
args=parser.parse_args()
if not args.serial.startswith("emulator-"): raise SystemExit("Use an emulator, never a physical device")
root=pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
adb=str(pathlib.Path(args.sdk)/'platform-tools/adb')
pkg='com.shifthud.migrationaudit'
assert pkg.endswith('.migrationaudit')
out=pathlib.Path(args.output or tempfile.mkdtemp(prefix='shifthud-migration-'));out.mkdir(parents=True,exist_ok=True)
def run(*command):
 p=subprocess.run([adb,'-s',args.serial,*command],capture_output=True);print(p.stdout.decode(errors='replace'),flush=True);p.check_returncode();return p.stdout
def phase(name):
 data=run('shell','am','instrument','-w','-r','-e','class','com.shifthud.backup.BackupDeviceAcceptanceTest','-e','phase',name,pkg+'.test/androidx.test.runner.AndroidJUnitRunner')
 (out/(name+'.log')).write_bytes(data)
 assert b'OK (1 test)' in data,name
# Reject standard production APKs before installation or any data reset.
aapt=sorted((pathlib.Path(args.sdk)/"build-tools").glob("*/aapt"))[-1]
for apk,expected in [("app/build/outputs/apk/debug/app-debug.apk",pkg),("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk",pkg+".test")]:
    badging=subprocess.check_output([str(aapt),"dump","badging",apk],text=True)
    actual=re.search(r"package: name='([^']+)'",badging).group(1)
    if actual!=expected: raise SystemExit("Build with -PmigrationAudit=true; refusing package "+actual)
if run('shell','getprop','ro.kernel.qemu').strip()!=b'1': raise SystemExit('Not an emulator')
run('install','-r','app/build/outputs/apk/debug/app-debug.apk')
run('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
run('shell','pm','clear',pkg)
phase('seed')
for name in ['backup-acceptance-expected.json','backup-acceptance.shifthud']:
 p=subprocess.run([adb,'-s',args.serial,'exec-out','run-as',pkg,'cat','files/'+name],capture_output=True,check=True)
 (out/name).write_bytes(p.stdout)
run('shell','pm','clear',pkg)
# Private app directory exists after clear; create only the synthetic fixtures directory.
run('shell','run-as',pkg,'mkdir','-p','files')
for name in ['backup-acceptance-expected.json','backup-acceptance.shifthud']:
 run('push',str(out/name),'/data/local/tmp/'+name)
 run('shell','run-as',pkg,'cp','/data/local/tmp/'+name,'files/'+name)
phase('restore_file')
run('shell','am','force-stop',pkg)
phase('verify')
phase('duplicate')
phase('reject')
phase('mark_recovery')
# Deliberate process death, then Application.onCreate must recover the journal.
run('shell','am','instrument','-w','-r','-e','class','com.shifthud.backup.BackupDeviceAcceptanceTest','-e','phase','interrupt_after_checkpoint',pkg+'.test/androidx.test.runner.AndroidJUnitRunner')
phase('verify_recovery')
phase('restore_file')
run('shell','am','force-stop',pkg)
phase('verify')
p=subprocess.run([adb,'-s',args.serial,'exec-out','run-as',pkg,'cat','files/migration-verification.txt'],capture_output=True,check=True)
(out/'verification.txt').write_bytes(p.stdout)
print('MIGRATION PASS — reports: '+str(out),flush=True)
