# CrazyEnchantments → Hearth 전체 대응표

기준: `6ecea9a19affa811a8e23dd47fe647482507b82c` · 원본 105종 · 정의/구체 모듈 구현 **105/105 (100%)**. 현재 원본 기본 설정은 105종 모두 true이며 비활성 전환/데이터 보존도 지원한다.

자동 검증은 수식/트리거 계획·모듈 등록·북 작업/PG/새 JVM 범위다. **105종 실제 플레이 수용률은 미측정**이며 정품 클라이언트 시험은 보류했다. 수치/최대 레벨은 원본을 시작 예제로 보존했으며 운영 밸런스로 확정하지 않았다. [전체 검증](enchant-system-verification.md)·[운영 가이드](../infrastructure/network/enchants.md)·[구조화 대응표](../verification/enchant-port-mapping.json)를 따른다.

| 원본 | 안정 ID / YAML | 원본 최대 레벨 | 트리거 | 구체 효과 | 상태 |
| --- | --- | --- | --- | --- | --- |
| Glowing | [hearth:ce_glowing](../src/main/resources/enchants/ce_glowing.yml) | 1 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| BurnShield | [hearth:ce_burnshield](../src/main/resources/enchants/ce_burnshield.yml) | 1 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Piercing | [hearth:ce_piercing](../src/main/resources/enchants/ce_piercing.yml) | 3 | ARROW_HIT | damage_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| OverLoad | [hearth:ce_overload](../src/main/resources/enchants/ce_overload.yml) | 5 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| SelfDestruct | [hearth:ce_selfdestruct](../src/main/resources/enchants/ce_selfdestruct.yml) | 5 | DEATH | explosion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Springs | [hearth:ce_springs](../src/main/resources/enchants/ce_springs.yml) | 5 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Gears | [hearth:ce_gears](../src/main/resources/enchants/ce_gears.yml) | 3 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Hulk | [hearth:ce_hulk](../src/main/resources/enchants/ce_hulk.yml) | 2 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Mermaid | [hearth:ce_mermaid](../src/main/resources/enchants/ce_mermaid.yml) | 3 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Ninja | [hearth:ce_ninja](../src/main/resources/enchants/ce_ninja.yml) | 2 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Vampire | [hearth:ce_vampire](../src/main/resources/enchants/ce_vampire.yml) | 5 | ATTACK | heal | 구현·자동 계약 확인 / 실제 플레이 보류 |
| LifeSteal | [hearth:ce_lifesteal](../src/main/resources/enchants/ce_lifesteal.yml) | 5 | ATTACK | heal | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Doctor | [hearth:ce_doctor](../src/main/resources/enchants/ce_doctor.yml) | 3 | ARROW_HIT | heal | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Boom | [hearth:ce_boom](../src/main/resources/enchants/ce_boom.yml) | 3 | ARROW_IMPACT | explosion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Venom | [hearth:ce_venom](../src/main/resources/enchants/ce_venom.yml) | 5 | ARROW_HIT | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| DoubleDamage | [hearth:ce_doubledamage](../src/main/resources/enchants/ce_doubledamage.yml) | 3 | ATTACK | damage_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| SlowMo | [hearth:ce_slowmo](../src/main/resources/enchants/ce_slowmo.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Blindness | [hearth:ce_blindness](../src/main/resources/enchants/ce_blindness.yml) | 2 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Viper | [hearth:ce_viper](../src/main/resources/enchants/ce_viper.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| AntiGravity | [hearth:ce_antigravity](../src/main/resources/enchants/ce_antigravity.yml) | 3 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| FastTurn | [hearth:ce_fastturn](../src/main/resources/enchants/ce_fastturn.yml) | 2 | ATTACK | damage_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| LightWeight | [hearth:ce_lightweight](../src/main/resources/enchants/ce_lightweight.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Blessed | [hearth:ce_blessed](../src/main/resources/enchants/ce_blessed.yml) | 3 | ATTACK | cleanse | 구현·자동 계약 확인 / 실제 플레이 보류 |
| FeedMe | [hearth:ce_feedme](../src/main/resources/enchants/ce_feedme.yml) | 5 | ATTACK | food | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Dizzy | [hearth:ce_dizzy](../src/main/resources/enchants/ce_dizzy.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Berserk | [hearth:ce_berserk](../src/main/resources/enchants/ce_berserk.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Cursed | [hearth:ce_cursed](../src/main/resources/enchants/ce_cursed.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Rekt | [hearth:ce_rekt](../src/main/resources/enchants/ce_rekt.yml) | 3 | ATTACK | damage_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| TreeFeller | [hearth:ce_treefeller](../src/main/resources/enchants/ce_treefeller.yml) | 5 | BREAK | area_break | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Enlightened | [hearth:ce_enlightened](../src/main/resources/enchants/ce_enlightened.yml) | 5 | DEFEND | heal | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Freeze | [hearth:ce_freeze](../src/main/resources/enchants/ce_freeze.yml) | 2 | DEFEND | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Fortify | [hearth:ce_fortify](../src/main/resources/enchants/ce_fortify.yml) | 3 | DEFEND | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Molten | [hearth:ce_molten](../src/main/resources/enchants/ce_molten.yml) | 5 | DEFEND | fire | 구현·자동 계약 확인 / 실제 플레이 보류 |
| PainGiver | [hearth:ce_paingiver](../src/main/resources/enchants/ce_paingiver.yml) | 4 | DEFEND | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Savior | [hearth:ce_savior](../src/main/resources/enchants/ce_savior.yml) | 5 | DEFEND | damage_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Nursery | [hearth:ce_nursery](../src/main/resources/enchants/ce_nursery.yml) | 5 | TICK | heal | 구현·자동 계약 확인 / 실제 플레이 보류 |
| AutoSmelt | [hearth:ce_autosmelt](../src/main/resources/enchants/ce_autosmelt.yml) | 5 | DROP | smelt | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Experience | [hearth:ce_experience](../src/main/resources/enchants/ce_experience.yml) | 3 | BLOCK_XP | xp_add | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Telepathy | [hearth:ce_telepathy](../src/main/resources/enchants/ce_telepathy.yml) | 1 | DROP | collect | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Haste | [hearth:ce_haste](../src/main/resources/enchants/ce_haste.yml) | 3 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Oxygenate | [hearth:ce_oxygenate](../src/main/resources/enchants/ce_oxygenate.yml) | 1 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| IceFreeze | [hearth:ce_icefreeze](../src/main/resources/enchants/ce_icefreeze.yml) | 1 | ARROW_HIT | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Lightning | [hearth:ce_lightning](../src/main/resources/enchants/ce_lightning.yml) | 1 | ARROW_IMPACT | damage, lightning_visual | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Decapitation | [hearth:ce_decapitation](../src/main/resources/enchants/ce_decapitation.yml) | 5 | KILL | head_drop | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Confusion | [hearth:ce_confusion](../src/main/resources/enchants/ce_confusion.yml) | 4 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Disarmer | [hearth:ce_disarmer](../src/main/resources/enchants/ce_disarmer.yml) | 3 | ATTACK | disarm | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Execute | [hearth:ce_execute](../src/main/resources/enchants/ce_execute.yml) | 5 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Headless | [hearth:ce_headless](../src/main/resources/enchants/ce_headless.yml) | 5 | KILL | head_drop | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Inquisitive | [hearth:ce_inquisitive](../src/main/resources/enchants/ce_inquisitive.yml) | 5 | MOB_XP | xp_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Insomnia | [hearth:ce_insomnia](../src/main/resources/enchants/ce_insomnia.yml) | 1 | EQUIP, DEFEND | damage, potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Nutrition | [hearth:ce_nutrition](../src/main/resources/enchants/ce_nutrition.yml) | 3 | ATTACK | saturation | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Obliterate | [hearth:ce_obliterate](../src/main/resources/enchants/ce_obliterate.yml) | 3 | ATTACK | knockback | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Paralyze | [hearth:ce_paralyze](../src/main/resources/enchants/ce_paralyze.yml) | 3 | ATTACK | damage, lightning_visual, potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| SkillSwipe | [hearth:ce_skillswipe](../src/main/resources/enchants/ce_skillswipe.yml) | 5 | ATTACK | xp_transfer | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Snare | [hearth:ce_snare](../src/main/resources/enchants/ce_snare.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Trap | [hearth:ce_trap](../src/main/resources/enchants/ce_trap.yml) | 2 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Wither | [hearth:ce_wither](../src/main/resources/enchants/ce_wither.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Rage | [hearth:ce_rage](../src/main/resources/enchants/ce_rage.yml) | 3 | ATTACK | rage | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Valor | [hearth:ce_valor](../src/main/resources/enchants/ce_valor.yml) | 2 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| SmokeBomb | [hearth:ce_smokebomb](../src/main/resources/enchants/ce_smokebomb.yml) | 3 | DEFEND | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Drunk | [hearth:ce_drunk](../src/main/resources/enchants/ce_drunk.yml) | 3 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Voodoo | [hearth:ce_voodoo](../src/main/resources/enchants/ce_voodoo.yml) | 3 | DEFEND | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| MultiArrow | [hearth:ce_multiarrow](../src/main/resources/enchants/ce_multiarrow.yml) | 5 | SHOOT | multi_arrow | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Recover | [hearth:ce_recover](../src/main/resources/enchants/ce_recover.yml) | 1 | KILL | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Cactus | [hearth:ce_cactus](../src/main/resources/enchants/ce_cactus.yml) | 3 | DEFEND | damage | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Rocket | [hearth:ce_rocket](../src/main/resources/enchants/ce_rocket.yml) | 3 | DEFEND | fall_guard, knockback | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Adrenaline | [hearth:ce_adrenaline](../src/main/resources/enchants/ce_adrenaline.yml) | 3 | DEFEND | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Angel | [hearth:ce_angel](../src/main/resources/enchants/ce_angel.yml) | 5 | TICK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| HellForged | [hearth:ce_hellforged](../src/main/resources/enchants/ce_hellforged.yml) | 4 | TICK | durability | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Commander | [hearth:ce_commander](../src/main/resources/enchants/ce_commander.yml) | 5 | TICK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| StormCaller | [hearth:ce_stormcaller](../src/main/resources/enchants/ce_stormcaller.yml) | 5 | DEFEND | damage, lightning_visual | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Leadership | [hearth:ce_leadership](../src/main/resources/enchants/ce_leadership.yml) | 5 | ATTACK | leadership | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Implants | [hearth:ce_implants](../src/main/resources/enchants/ce_implants.yml) | 5 | TICK | food | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Wings | [hearth:ce_wings](../src/main/resources/enchants/ce_wings.yml) | 1 | EQUIP | flight | 구현·자동 계약 확인 / 실제 플레이 보류 |
| VeinMiner | [hearth:ce_veinminer](../src/main/resources/enchants/ce_veinminer.yml) | 6 | BREAK | area_break | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Blast | [hearth:ce_blast](../src/main/resources/enchants/ce_blast.yml) | 3 | BREAK | area_break | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Furnace | [hearth:ce_furnace](../src/main/resources/enchants/ce_furnace.yml) | 1 | DROP | smelt | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Blizzard | [hearth:ce_blizzard](../src/main/resources/enchants/ce_blizzard.yml) | 1 | TICK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| AcidRain | [hearth:ce_acidrain](../src/main/resources/enchants/ce_acidrain.yml) | 3 | TICK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| SandStorm | [hearth:ce_sandstorm](../src/main/resources/enchants/ce_sandstorm.yml) | 1 | TICK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Radiant | [hearth:ce_radiant](../src/main/resources/enchants/ce_radiant.yml) | 3 | TICK | fire | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Intimidate | [hearth:ce_intimidate](../src/main/resources/enchants/ce_intimidate.yml) | 2 | TICK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Pull | [hearth:ce_pull](../src/main/resources/enchants/ce_pull.yml) | 3 | ARROW_HIT | pull | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Tamer | [hearth:ce_tamer](../src/main/resources/enchants/ce_tamer.yml) | 5 | ATTACK, DEFEND | summon | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Guards | [hearth:ce_guards](../src/main/resources/enchants/ce_guards.yml) | 3 | ATTACK, DEFEND | summon | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Infestation | [hearth:ce_infestation](../src/main/resources/enchants/ce_infestation.yml) | 2 | ATTACK, DEFEND | summon | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Necromancer | [hearth:ce_necromancer](../src/main/resources/enchants/ce_necromancer.yml) | 3 | ATTACK, DEFEND | summon | 구현·자동 계약 확인 / 실제 플레이 보류 |
| BeeKeeper | [hearth:ce_beekeeper](../src/main/resources/enchants/ce_beekeeper.yml) | 2 | ATTACK, DEFEND | summon | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Maneuver | [hearth:ce_maneuver](../src/main/resources/enchants/ce_maneuver.yml) | 3 | DEFEND | cancel_damage | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Crouch | [hearth:ce_crouch](../src/main/resources/enchants/ce_crouch.yml) | 3 | DEFEND | damage_multiplier | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Shockwave | [hearth:ce_shockwave](../src/main/resources/enchants/ce_shockwave.yml) | 3 | DEFEND | knockback | 구현·자동 계약 확인 / 실제 플레이 보류 |
| SystemReboot | [hearth:ce_systemreboot](../src/main/resources/enchants/ce_systemreboot.yml) | 3 | DEFEND | cancel_damage, heal | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Sticky-Shot | [hearth:ce_sticky_shot](../src/main/resources/enchants/ce_sticky_shot.yml) | 3 | ARROW_IMPACT | temporary_block | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Disorder | [hearth:ce_disorder](../src/main/resources/enchants/ce_disorder.yml) | 1 | ATTACK | shuffle_hotbar | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Charge | [hearth:ce_charge](../src/main/resources/enchants/ce_charge.yml) | 5 | KILL | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Revenge | [hearth:ce_revenge](../src/main/resources/enchants/ce_revenge.yml) | 1 | ALLY_DEATH | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| BattleCry | [hearth:ce_battlecry](../src/main/resources/enchants/ce_battlecry.yml) | 5 | ATTACK | knockback | 구현·자동 계약 확인 / 실제 플레이 보류 |
| DemonForged | [hearth:ce_demonforged](../src/main/resources/enchants/ce_demonforged.yml) | 5 | ATTACK | durability | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Famished | [hearth:ce_famished](../src/main/resources/enchants/ce_famished.yml) | 3 | ATTACK | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| GreenThumb | [hearth:ce_greenthumb](../src/main/resources/enchants/ce_greenthumb.yml) | 3 | INTERACT | farm | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Harvester | [hearth:ce_harvester](../src/main/resources/enchants/ce_harvester.yml) | 1 | BREAK | area_break | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Tiller | [hearth:ce_tiller](../src/main/resources/enchants/ce_tiller.yml) | 1 | INTERACT | farm | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Planter | [hearth:ce_planter](../src/main/resources/enchants/ce_planter.yml) | 1 | INTERACT | farm | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Cyborg | [hearth:ce_cyborg](../src/main/resources/enchants/ce_cyborg.yml) | 1 | EQUIP | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
| Sniper | [hearth:ce_sniper](../src/main/resources/enchants/ce_sniper.yml) | 2 | ARROW_HIT | potion | 구현·자동 계약 확인 / 실제 플레이 보류 |
