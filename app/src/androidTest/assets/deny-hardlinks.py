"""Test-only: deny native link/linkat in this process tree, without changing Android policy.

Linux UAPI syscall numbers / seccomp definitions:
https://github.com/torvalds/linux/blob/master/arch/x86/entry/syscalls/syscall_64.tbl
https://github.com/torvalds/linux/blob/master/include/uapi/asm-generic/unistd.h
https://github.com/torvalds/linux/blob/master/include/uapi/linux/seccomp.h
"""
import ctypes
import errno
import os
import sys


class Filter(ctypes.Structure):
    _fields_ = [("code", ctypes.c_ushort), ("jt", ctypes.c_ubyte),
                ("jf", ctypes.c_ubyte), ("k", ctypes.c_uint)]


class Program(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort), ("filter", ctypes.POINTER(Filter))]


# Emulators can spoof uname to ARM even while executing an x86_64 binary.
with open("/usr/bin/python3", "rb") as executable:
    header = executable.read(20)
assert header[:6] == b"\x7fELF\x02\x01", "Expected a little-endian ELF64 test interpreter"
machine = int.from_bytes(header[18:20], "little")
arch, calls = {62: (0xC000003E, [86, 265]), 183: (0xC00000B7, [37])}[machine]
# Check the ABI before matching syscall numbers. Refuse unexpected ABIs rather than silently pass.
instructions = [Filter(0x20, 0, 0, 4), Filter(0x15, 1, 0, arch),
                Filter(0x06, 0, 0, 0x80000000), Filter(0x20, 0, 0, 0)]
for number in calls:
    instructions += [Filter(0x15, 0, 1, number), Filter(0x06, 0, 0, 0x00050000 | errno.EACCES)]
instructions += [Filter(0x06, 0, 0, 0x7FFF0000)]
array = (Filter * len(instructions))(*instructions)
program = Program(len(array), array)
libc = ctypes.CDLL(None, use_errno=True)
libc.prctl.argtypes = [ctypes.c_int] + [ctypes.c_ulong] * 4
libc.prctl.restype = ctypes.c_int
for option, value, pointer in [(38, 1, 0), (22, 2, ctypes.addressof(program))]:
    print(f"Installing test filter: ELF machine={machine}, prctl={option}", flush=True)
    if libc.prctl(option, value, pointer, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "Cannot install test-only hard-link filter")
print("Test-only hard-link filter installed", flush=True)
os.execv(sys.argv[1], sys.argv[1:])
