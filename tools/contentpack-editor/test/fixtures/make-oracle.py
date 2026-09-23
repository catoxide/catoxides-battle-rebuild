import io, nbtlib
from nbtlib import Int, String, Byte, Long, Float, Compound, List

tree = Compound({
    'size': List[Int]([31, 19, 16]),
    'name': String('桥'),
    'n': Byte(-5),
    'big': Long(9007199254740991),
    'f': Float(1.5),
    'nested': Compound({
        'inner': Int(42),
        'list': List[Compound]([Compound({'k': String('v1')}), Compound({'k': String('v2')})]),
    }),
})
base = r'D:\Software\MC develop pack\Neoforge\catoxides-battle-rebuild\.scratch'
for bo, fn in (('little', 'oracle-le.nbt'), ('big', 'oracle-be.nbt')):
    f = nbtlib.File(tree)
    buf = io.BytesIO()
    f.write(buf, bo)
    data = buf.getvalue()
    open(base + '\\' + fn, 'wb').write(data)
    print(fn, 'len', len(data), 'first24:', data[:24].hex(' '))
print('oracle written')
