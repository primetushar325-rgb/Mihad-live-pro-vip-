#!/usr/bin/env python3
"""Converts an aapt2-generated R.java into R.kt.

kotlinc cannot emit .class files for .java inputs it merely *resolves*, so the
project compiles a hand-shaped Kotlin R object instead. Values are the exact
int IDs aapt2 produced, therefore runtime resource lookups are identical to a
Gradle build.
"""
import re
import sys

def main(rjava_path, out_path):
    src = open(rjava_path).read()
    pkg = re.search(r'package ([\w.]+);', src).group(1)
    out = ['package ' + pkg, '',
           '@Suppress("ClassName", "ConstPropertyName")', 'object R {']
    stack = ['R']
    for raw in src.splitlines():
        line = raw.strip()
        m = re.match(r'public static final class (\w+)\s*\{', line)
        if m:
            out.append('    ' * len(stack) + 'object ' + m.group(1) + ' {')
            stack.append(m.group(1))
            continue
        m = re.match(r'public static final int (\w+)=(0x[0-9a-fA-F]+);', line)
        if m:
            out.append('    ' * len(stack) + 'const val ' + m.group(1) + ': Int = ' + m.group(2))
            continue
        if line == '}' and len(stack) > 1:
            stack.pop()
            out.append('    ' * len(stack) + '}')
    while stack:
        out.append('    ' * (len(stack) - 1) + '}')
        stack.pop()
    open(out_path, 'w').write('\n'.join(out) + '\n')
    print('wrote', out_path)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
