package com.jtang.gameserver.module.sysmail.facade.impl;

import static com.jiatang.common.GameStatusCodeConstant.SYSMAIL_ATTACH_HAS_RECEIVED;
import static com.jiatang.common.GameStatusCodeConstant.SYSMAIL_NOT_EXISTS;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

import com.jtang.core.lock.ChainLock;
import com.jtang.core.lock.LockUtils;
import com.jtang.core.model.RewardObject;
import com.jtang.core.result.Result;
import com.jtang.core.result.TResult;
import com.jtang.core.rhino.FormulaHelper;
import com.jtang.core.schedule.Schedule;
import com.jtang.core.utility.TimeUtils;
import com.jtang.gameserver.component.Game;
import com.jtang.gameserver.component.oss.GameOssLogger;
import com.jtang.gameserver.dataconfig.model.SysmailConfig;
import com.jtang.gameserver.dataconfig.service.SysmailService;
import com.jtang.gameserver.dbproxy.entity.Sysmail;
import com.jtang.gameserver.module.equip.facade.EquipFacade;
import com.jtang.gameserver.module.equip.type.EquipAddType;
import com.jtang.gameserver.module.goods.facade.GoodsFacade;
import com.jtang.gameserver.module.goods.type.GoodsAddType;
import com.jtang.gameserver.module.hero.facade.HeroSoulFacade;
import com.jtang.gameserver.module.hero.type.HeroSoulAddType;
import com.jtang.gameserver.module.sysmail.dao.SysmailDao;
import com.jtang.gameserver.module.sysmail.facade.SysmailFacade;
import com.jtang.gameserver.module.sysmail.help.SysmailPushHelper;
import com.jtang.gameserver.module.sysmail.type.GetType;
import com.jtang.gameserver.module.sysmail.type.SysmailType;
import com.jtang.gameserver.module.user.facade.ActorFacade;
import com.jtang.gameserver.module.user.type.GoldAddType;
import com.jtang.gameserver.server.session.PlayerSession;

@Component
public class SysmailFacadeImpl implements SysmailFacade,ApplicationListener<ContextRefreshedEvent> {
	private static Logger LOGGER = LoggerFactory.getLogger(SysmailFacadeImpl.class);

	@Autowired
	EquipFacade equipFacade;
	@Autowired
	GoodsFacade goodsFacade;
	@Autowired
	ActorFacade actorFacade;
	@Autowired
	HeroSoulFacade heroSoulFacade;
	@Autowired
	SysmailDao sysmailDao;
	@Autowired
	private Schedule schedule;
	@Autowired
	private PlayerSession playerSession;

	/**
	 * TODO >每次重新登陆则从数据库中拉一次最新的 系统邮件 >dao中缓存每个角色的邮件列表。删除策略根据CacheListner来操作
	 */

	@Override
	public List<Sysmail> getList(long actorId) {
		return sysmailDao.getList(actorId, true);
	}

	@Override
	public TResult<Sysmail> getAttach(long actorId, long sysMailId) {
		Sysmail sysmail = sysmailDao.get(actorId, sysMailId);
		if (sysmail == null) {
			return TResult.valueOf(SYSMAIL_NOT_EXISTS);
		}

		if (sysmail.isGet == GetType.GET.getCode()) {
			return TResult.valueOf(SYSMAIL_ATTACH_HAS_RECEIVED);
		}

		ChainLock lock = LockUtils.getLock(sysmail);
		try {
			lock.lock();
			for (RewardObject reward : sysmail.getAttachGoodsList()) {
				sendReward(actorId, reward);
			}
			sysmail.getAttach();
		} catch (Exception ex) {
			LOGGER.error("", ex);
		} finally {
			lock.unlock();
		}
		sysmailDao.update(sysmail);
		remove(actorId, sysMailId);
		return TResult.sucess(sysmail);
	}

	@Override
	public Result remove(long actorId, long sysMailId) {
		Sysmail mail = sysmailDao.get(actorId, sysMailId);
		if (mail != null) {
			GameOssLogger.sysmailRemove(Game.getServerId(), mail.getPkId(), mail.ownerActorId, mail.content, mail.reward2String(), mail.sendTime, mail.isGet);
		}
		sysmailDao.remove(actorId, sysMailId);
		return Result.valueOf();
	}

	private boolean sendReward(long actorId, RewardObject rewardObject) {
		switch (rewardObject.rewardType) {
		case EQUIP: {
			equipFacade.addEquip(actorId, EquipAddType.SYSMAIL_REWARD, rewardObject.id);
			break;
		}
		case GOLD: {
			actorFacade.addGold(actorId, GoldAddType.SYSMAIL_REWARD, rewardObject.num);
			break;
		}
		case HEROSOUL: {
			heroSoulFacade.addSoul(actorId, HeroSoulAddType.SYSMAIL_REWARD, rewardObject.id, rewardObject.num);
			break;
		}
		case GOODS: {
			goodsFacade.addGoodsVO(actorId, GoodsAddType.SYSMAIL_REWARD, rewardObject.id, rewardObject.num);
			break;
		}
		default:
			return false;
		}
		return true;
	}

	@Override
	public void sendSysmail(long actorId, SysmailType mailType, List<RewardObject> list, Number... args) {
		SysmailConfig config = SysmailService.get(mailType.getCode());
		if (config == null) {
			LOGGER.error("SysmailConfig.xml error");
		}
		Sysmail sysmail = Sysmail.valueOf(actorId);
		sysmail.setAttachGoodsList(list);
		sysmail.sendTime = TimeUtils.getNow();
		try {
			sysmail.content = FormulaHelper.excuteString(config.text, args);
		} catch (NullPointerException e1) {
			LOGGER.error("SysmailConfig or text is null.", e1);
			sysmail.content = "";
		} catch (IllegalFormatException e2) {
			LOGGER.error("sendSysmail param error.", e2);
			sysmail.content = config.text;
		}
		sysmailDao.save(sysmail);
		SysmailPushHelper.pushNewSysmail(actorId, sysmail);
	}
	@Override
	public void sendSysmail(long actorId, SysmailType mailType, List<RewardObject> list, String... args) {
		SysmailConfig config = SysmailService.get(mailType.getCode());
		if (config == null) {
			LOGGER.error("SysmailConfig.xml error");
		}
		Sysmail sysmail = Sysmail.valueOf(actorId);
		sysmail.setAttachGoodsList(list);
		sysmail.sendTime = TimeUtils.getNow();
		try {
			sysmail.content = String.format(config.text, args);
		} catch (NullPointerException e1) {
			LOGGER.error("SysmailConfig or text is null.", e1);
			sysmail.content = "";
		} catch (IllegalFormatException e2) {
			LOGGER.error("sendSysmail param error.", e2);
			sysmail.content = config.text;
		}
		sysmailDao.save(sysmail);
		SysmailPushHelper.pushNewSysmail(actorId, sysmail);
	}

	@Override
	public Result channelSendMail(long actorId, List<RewardObject> list, String text) {
		Sysmail sysmail = Sysmail.valueOf(actorId);
		sysmail.setAttachGoodsList(list);
		sysmail.sendTime = TimeUtils.getNow();
		sysmail.content = text;
		sysmailDao.save(sysmail);
		SysmailPushHelper.pushNewSysmail(actorId, sysmail);
		return Result.valueOf();
	}

	@Override
	public Result oneKeyGetAttach(long actorId) {
		List<Sysmail> list = new ArrayList<>(sysmailDao.getList(actorId, true));
		if(list.isEmpty()){
			return Result.valueOf(SYSMAIL_NOT_EXISTS);
		}
		for(Sysmail sysmail:list){
			getAttach(actorId, sysmail.getPkId());
		}
		return Result.valueOf();
	}

	@Override
	public void onApplicationEvent(ContextRefreshedEvent arg0) {
		/*schedule.addEveryMinute(new Runnable() {
			
			@Override
			public void run() {
				for(Long actorId : playerSession.onlineActorList()){
					List<Sysmail> dbMailList = sysmailDao.loadFromDB(actorId);
					List<Sysmail> cacheMailList = sysmailDao.loadFromCache(actorId);
					for(Sysmail dbmail : dbMailList){
						boolean isPush = true;
						for (Sysmail cacheMail : cacheMailList) {
							if(cacheMail.getPkId().equals(dbmail.getPkId())){
								isPush = false;
								break;
							}
						}
						if(isPush){
							sysmailDao.flushCache(actorId,dbmail);
							SysmailPushHelper.pushNewSysmail(actorId, dbmail);
						}
					}
				}
			}
		}, 1);*/
	}

}
