"""Hilfsprogramme (git, openssl, adb, …) absturzsicher starten.

macOS: Ein Python-Prozess mit Threads und offenen Netzwerkverbindungen stürzt beim klassischen fork() ab
("crashed on child side of fork pre-exec", Network-Framework). posix_spawn kennt das Problem nicht.
CPython nimmt posix_spawn nur mit vollem Programmpfad und close_fds=False – das stellen wir hier sicher.
"""
import shutil
import subprocess


def _prepare(args, kw):
    args = list(args)
    if '/' not in args[0]:
        args[0] = shutil.which(args[0]) or args[0]
    kw.setdefault('close_fds', False)
    return args, kw


def run(args, **kw):
    args, kw = _prepare(args, kw)
    return subprocess.run(args, **kw)


def popen(args, **kw):
    args, kw = _prepare(args, kw)
    return subprocess.Popen(args, **kw)
