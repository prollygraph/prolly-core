# Golden storage fixture (v-current)
Written by GoldenStoreFixtureRegen; opened by GoldenStoreOpenTest every build.
A failing open test = the storage on-disk format changed (node chunks, Database
commit encoding, branch refs): fix the code, or regenerate deliberately
(mvn -pl prolly-storage test -Dtest=GoldenStoreFixtureRegen -Dgolden.regen=true)
and write the CHANGELOG format note. head.txt records the expected main head at
regen time (commit hashes embed timestamps — bytes churn per regen; the open
test's semantic assertions are the review surface).
