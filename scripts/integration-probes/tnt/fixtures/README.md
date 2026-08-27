# TNT duplication regression fixture

`osc-tnt-duper.nbt.b64` is a compressed vanilla structure encoded as Base64 so
the Windows test harness can reproduce it without WorldEdit, Litematica, Python,
or network access.

The 17 x 12 x 7 fixture is derived from the public
`OSC 6.1 Dupers 1 Module [RB V6.0]` design by Konsti_lol. The regression uses
only its ten-TNT duplication module. It verifies piston update order, TNT block
retention, Bukkit spawn events, initial TNT position, motion, and fuse state.
