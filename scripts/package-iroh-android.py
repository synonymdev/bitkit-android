#!/usr/bin/env python3
"""Keep the published Kotlin classes and package aligned native slices for Bitkit's ABIs."""
from pathlib import Path
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile

work, output, ndk = map(Path, sys.argv[1:4])
revision = sys.argv[4]
abis = ('arm64-v8a', 'armeabi-v7a', 'x86_64')
sha256 = lambda data: hashlib.sha256(data).hexdigest()
original = work / 'published.aar'
assert sha256(original.read_bytes()) == 'ed747f627da6dad314b25b9ff17d38232d8d75cb31e663af348368e6be845ab8'
natives = {}
alignments = {}
checksums = {
    key: int(value) for key, value in re.findall(
        r'lib\.(uniffi_iroh_ffi_checksum_\w+)\(\) != (\d+)',
        (work / 'source/kotlin/lib/src/main/kotlin/computer/iroh/iroh_ffi.kt').read_text(),
    )
}
assert len(checksums) == 141, 'Unexpected generated API'
checksums['ffi_iroh_ffi_uniffi_contract_version'] = 30
objdump = next((ndk / 'toolchains/llvm/prebuilt').glob('*/bin/llvm-objdump'))
for abi in abis:
    data = (work / 'jni' / abi / 'libiroh_ffi.so').read_bytes()
    assert data[:4] == b'\x7fELF' and data[5] == 1, f'Unsupported ELF: {abi}'
    wide = data[4] == 2
    phoff = struct.unpack_from('<Q' if wide else '<I', data, 32 if wide else 28)[0]
    phsize, phnum = struct.unpack_from('<HH', data, 54 if wide else 42)
    loads = []
    for index in range(phnum):
        offset = phoff + index * phsize
        if struct.unpack_from('<I', data, offset)[0] != 1:
            continue
        if wide:
            file_offset, address = struct.unpack_from('<QQ', data, offset + 8)
            alignment = struct.unpack_from('<Q', data, offset + 48)[0]
        else:
            file_offset, address = struct.unpack_from('<II', data, offset + 4)
            alignment = struct.unpack_from('<I', data, offset + 28)[0]
        assert alignment >= 16384 and file_offset % 16384 == address % 16384, f'Unaligned ELF: {abi}'
        loads.append(alignment)
    assert loads, f'Missing ELF load segments: {abi}'
    natives[f'jni/{abi}/libiroh_ffi.so'] = data
    alignments[abi] = loads
    command = [str(objdump), '-d', '--disassemble-symbols=' + ','.join(checksums)]
    if abi == 'armeabi-v7a':
        command.append('--triple=thumbv7-linux-androideabi')
    command.append(str(work / 'jni' / abi / 'libiroh_ffi.so'))
    disassembly = subprocess.check_output(command, text=True)
    actual = {}
    for match in re.finditer(r'<([^>]+)>:\n([^\n]+)', disassembly):
        immediate = re.search(r'(?:#|\$)0x([\da-f]+)', match[2])
        if immediate:
            actual[match[1]] = int(immediate[1], 16)
    assert actual == checksums, f'Native API checksum mismatch: {abi}'

output.parent.mkdir(parents=True, exist_ok=True)
temporary = output.with_suffix('.aar.tmp')
with zipfile.ZipFile(original) as source, zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as dest:
    for info in source.infolist():
        if not info.filename.startswith('jni/'):
            dest.writestr(info, source.read(info))
    for name, data in natives.items():
        info = zipfile.ZipInfo(name, date_time=(1980, 2, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        dest.writestr(info, data)
with zipfile.ZipFile(original) as before, zipfile.ZipFile(temporary) as after:
    for name in before.namelist():
        if not name.startswith('jni/'):
            assert before.read(name) == after.read(name), f'Changed published content: {name}'
os.replace(temporary, output)
record = {
    'upstream_revision': revision,
    'version': '1.1.0',
    'source_archive_sha256': sha256((work / 'source.tar.gz').read_bytes()),
    'cargo_lock_sha256': sha256((work / 'source/Cargo.lock').read_bytes()),
    'published_aar_sha256': sha256(original.read_bytes()),
    'aligned_aar_sha256': sha256(output.read_bytes()),
    'rustc': subprocess.check_output(['rustc', '--version'], text=True).strip(),
    'cargo_ndk': subprocess.check_output(['cargo', 'ndk', '--version'], text=True).strip(),
    'ndk': (ndk / 'source.properties').read_text(),
    'rustflags': '-C link-arg=-Wl,-z,max-page-size=16384',
    'android_api': 28,
    'published_non_native_content_unchanged': True,
    'verified_native_api_checksums_per_abi': 141,
    'verified_uniffi_contract_version': 30,
    'abis': {abi: {'sha256': sha256(natives[f'jni/{abi}/libiroh_ffi.so']), 'load_alignment': alignments[abi]} for abi in abis},
}
output.with_suffix('.json').write_text(json.dumps(record, indent=2) + '\n')
print(output)
print(record['aligned_aar_sha256'])
