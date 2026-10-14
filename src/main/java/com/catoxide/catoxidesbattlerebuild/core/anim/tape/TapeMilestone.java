package com.catoxide.catoxidesbattlerebuild.core.anim.tape;

/**
 * 里程碑：纸带 t 到达 atSec 时一次性执行的动作（单向提交，永不重放）。
 *
 * @param atSec   段内时间戳（相对段起点，秒；0 <= atSec <= segment.untilSec）
 * @param action  执行的动作
 */
public record TapeMilestone(double atSec, TapeAction action) {}
