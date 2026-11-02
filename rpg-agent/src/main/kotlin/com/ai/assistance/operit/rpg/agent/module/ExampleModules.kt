package com.ai.assistance.operit.rpg.agent.module

import com.ai.assistance.operit.rpg.agent.contract.*
import com.ai.assistance.operit.rpg.agent.rules.*
import com.ai.assistance.operit.rpg.agent.tools.StrictJson
import kotlinx.serialization.encodeToString
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Original, data-only examples. They are distributed as external packs, never silently installed. */
object ExampleModules {
    fun all():List<ValidatedModule> = listOf(
        build("winter-post","雪线驿站失踪案",listOf("驿站大厅","马厩","旧信塔","林间岔路","山腰药棚","封雪隘口"),listOf("掌柜沈禾","驿卒何远","车夫鹿鸣","药师苏青","猎户章白","信使童舟","巡山员孟石","厨师阿陶","学徒木叶","织工乔岚","守塔人池安","失踪驿使江川"),listOf("查清驿使最后的路线","找到被截留的冬季药包","护送驿使与药包回站"),"一封未送达的回信和两串不一致的马蹄印，让封雪前的驿站陷入猜疑。", "江川为保护药包藏在封雪隘口的旧窖里，截信者是受伤求药的外乡人。"),
        build("red-ridge","赤岭边境巡护",listOf("巡护所","风标台","牧民营地","赤石沟","旧测站","界碑山脊"),listOf("队长陆衡","向导娜依","牧人巴图","水文员闻溪","修械师梁羽","学者陈砚","巡护员麦川","商队长阿棠","看井人高栎","医护林遥","测绘员宋沙","新兵顾秋"),listOf("核实水源与边界异常","协调牧民和测绘队通行","修复风标并完成巡护报告"),"边境风标连续失灵，牧民与测绘队互相指责。巡护队必须在沙暴前完成调查。", "旧测站排水槽堵塞改变了季节水路，风标零件被移作修井，冲突并非敌对势力所为。")
    )
    private fun build(id:String,title:String,places:List<String>,names:List<String>,goals:List<String>,description:String,truth:String):ValidatedModule {
        val rulesText="""# 原创通用百分骰规则
## 行动检定
调查、交涉与技能检定使用百分骰。掷出1至100，点数小于等于技能加难度修正即成功。普通修正0，困难修正-20，容易修正20。失败仍推进游戏时间，不自动丢失生命或物品。
## 移动和等待
角色可以沿地图直接连接移动，每次移动推进10分钟。等待可以推进1至60分钟。未连接地点不可直接移动。
## 人物与物品
角色可与同地点人物交涉。信任变化每次不超过20，总值限制-100至100。只有公开事实可通过告知建立人物知情关系。物品转移需要当前拥有者或在场接收者。
## 调查与线索
调查当前地点可以依照预设行动公开该处线索。章节目标需要相关线索成立后推进，不得凭旁白宣告完成。
"""
        val setting="# $title\n\n$description\n\n## 主持资料\n$truth\n"
        val src=SourceRef("module/sources/rules.md",digest(rulesText),"行动检定","module_rule")
        val travel=src.copy(anchor="移动和等待")
        val people=src.copy(anchor="人物与物品")
        val clues=src.copy(anchor="调查与线索")
        val settingSource=SourceRef("module/sources/setting.md",digest(setting),"主持资料","module_custom")
        val evidence=listOf(RuleEvidence("$id:check",src,"调查、交涉与技能检定使用百分骰。掷出1至100，点数小于等于技能加难度修正即成功。普通修正0，困难修正-20，容易修正20。失败仍推进游戏时间，不自动丢失生命或物品。",Visibility(Audience.PUBLIC)),RuleEvidence("$id:travel",travel,"角色可以沿地图直接连接移动，每次移动推进10分钟。等待可以推进1至60分钟。未连接地点不可直接移动。",Visibility(Audience.PUBLIC)),RuleEvidence("$id:people",people,"角色可与同地点人物交涉。信任变化每次不超过20，总值限制-100至100。只有公开事实可通过告知建立人物知情关系。物品转移需要当前拥有者或在场接收者。",Visibility(Audience.PUBLIC)),RuleEvidence("$id:clues",clues,"调查当前地点可以依照预设行动公开该处线索。章节目标需要相关线索成立后推进，不得凭旁白宣告完成。",Visibility(Audience.PUBLIC)))
        fun loc(i:Int)="$id:location-$i"
        val locations=places.mapIndexed { i,p -> Location(loc(i),p,"${p}中留下了可调查的足迹、记录与当地人的日常。",listOfNotNull(if(i>0)loc(i-1) else null,if(i<5)loc(i+1) else null)) }
        val npcs=names.mapIndexed { i,n -> NpcState("$id:npc-$i",n,loc(i/2),"idle",if(i%2==0)"谨慎，重视承诺" else "直率，关注实际困难",when(i%3) { 0 -> "让本地人安全过冬"; 1 -> "查清误会并获得信任"; else -> "完成手头工作" }) }
        val factTexts=if(id=="winter-post")listOf("大厅的值班簿缺少最后一页。","马厩两串蹄印分别通向信塔和山路。","旧信塔留着写有药棚地址的回信。","岔路标记显示驿使主动离开了大道。","药棚有给受伤外乡人的包扎布。",truth) else listOf("巡护日志记载风标与水位同日异常。","风标台缺失的零件没有被破坏。","牧民营地最近为修井借过金属件。","赤石沟的新水痕来自测站方向。","旧测站排水槽被砂石堵住。",truth)
        val facts=factTexts.mapIndexed { i,t -> Fact("$id:fact-$i",t,Visibility(Audience.GM),i==5,settingSource) }
        val objectives=goals.mapIndexed { i,g -> Objective("$id:objective-$i",g,i+1,"active") }
        val actions=locations.mapIndexed { i,l ->
            val time=Effect("time","world",amount=10,source=clues)
            ActionDefinition("$id:investigate-$i","调查${l.title}","investigate",listOf(l.id),"调查","normal","$id:clues",listOf(Condition("location",targetId=l.id)),listOf(Effect("reveal_fact",facts[i].id,source=clues),time),listOf(time))
        }
        val world=ModuleWorld(loc(0),listOf(CharacterTemplate("$id:traveler","巡行者",mapOf("调查" to 65,"交涉" to 60,"体能" to 55),mapOf("生命" to 10,"补给" to 8))),locations,npcs,listOf(Item("$id:lantern","提灯","player",1),Item("$id:kit","急救包","player",1)),facts,objectives,actions,GenerationPolicy(setOf("npc","location_detail"),12,setOf(facts.last().id)))
        val rules=ModuleRules("generic-d100-v1","1",setOf("调查","交涉","体能"),mapOf("生命" to ResourceBounds(0,10),"补给" to ResourceBounds(0,20)),mapOf("normal" to 0,"hard" to -20,"easy" to 20),GenericD100.effects,evidence,"分钟")
        val draft=ModuleDraft(id,"1.0.0",title,"CGM2026",description,world,rules,mapOf("module/sources/rules.md" to rulesText,"module/sources/setting.md" to setting))
        return ModuleValidator.validateDraft(StrictJson.json.encodeToString(draft))
    }
    fun writeZip(module:ValidatedModule,output:OutputStream) {
        val files=module.originalFiles+mapOf("module/manifest.json" to StrictJson.json.encodeToString(module.manifest))
        ZipOutputStream(output).use { zip -> files.toSortedMap().forEach { (path,text) -> zip.putNextEntry(ZipEntry(path).also { it.time=0 }); zip.write(text.toByteArray()); zip.closeEntry() } }
    }
}
