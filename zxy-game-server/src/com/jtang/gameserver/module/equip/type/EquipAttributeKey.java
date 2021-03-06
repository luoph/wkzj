package com.jtang.gameserver.module.equip.type;

/**
 * 装备属性key
 * @author 0x737263
 *
 */
public enum EquipAttributeKey {

	/** 等级 */
	LEVEL(1),

	/** 攻击 */
	ATK(2),

	/** 防御 */
	DEFENSE(3),

	/** HP */
	HP(4),

	/** 射程 */
	ATTACK_SCOPE(5),

	/**已精炼次数  */
	REFINE_NUM(6),

	/**强化  */
	ENHANCED_NUM(7), 
	
	/**最大可精炼次数*/
	MAX_REFINE_NUM(8),
	
	/** 消耗金币*/
	COST_GOLD(9),
	
	/** 消耗石头*/
	COST_STONE(10),
	
	/** 突破次数*/
	DEVELOP_NUM(11),
	
	;
	
	private byte code;

	private EquipAttributeKey(int code) {
		this.code = (byte) code;
	}

	public byte getCode() {
		return code;
	}

	public void setCode(byte code) {
		this.code = code;
	}

}
