package us.ajg0702.queue.common;

import org.spongepowered.configurate.ConfigurateException;
import us.ajg0702.queue.api.*;
import us.ajg0702.queue.api.events.Event;
import us.ajg0702.queue.api.events.utils.EventReceiver;
import us.ajg0702.queue.api.premium.Logic;
import us.ajg0702.queue.api.premium.LogicGetter;
import us.ajg0702.queue.api.queues.QueueServer;
import us.ajg0702.queue.api.util.QueueLogger;
import us.ajg0702.queue.common.utils.LogConverter;
import us.ajg0702.queue.logic.LogicGetterImpl;
import us.ajg0702.utils.common.Config;
import us.ajg0702.utils.common.Messages;
import us.ajg0702.utils.common.Updater;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

public class QueueMain extends AjQueueAPI {

    private static QueueMain instance;
    public static QueueMain getInstance() {
        return instance;
    }

    private double timeBetweenPlayers;

    protected ServerTimeManagerImpl serverTimeManager = new ServerTimeManagerImpl();

    @Override
    public ServerTimeManager getServerTimeManager() {
        return serverTimeManager;
    }

    @Override
    public double getTimeBetweenPlayers() {
        return timeBetweenPlayers;
    }
    @Override
    public void setTimeBetweenPlayers() {
        this.timeBetweenPlayers = config.getDouble("wait-time");
    }

    private Config config;
    @Override
    public Config getConfig() {
        return config;
    }

    private Messages messages;
    @Override
    public Messages getMessages() {
        return messages;
    }

    private AliasManager aliasManager;
    @Override
    public AliasManager getAliasManager() {
        return aliasManager;
    }

    private Logic logic;
    @Override
    public Logic getLogic() {
        return logic;
    }

    @Override
    public boolean isPremium() {
        return getLogic().isPremium();
    }

    private final PlatformMethods platformMethods;
    @Override
    public PlatformMethods getPlatformMethods() {
        return platformMethods;
    }

    private final QueueLogger logger;
    @Override
    public QueueLogger getLogger() {
        return logger;
    }

    private final TaskManager taskManager = new TaskManager(this);
    public TaskManager getTaskManager() {
        return taskManager;
    }

    private final EventHandler eventHandler = new EventHandlerImpl(this);
    @Override
    public EventHandler getEventHandler() {
        return eventHandler;
    }

    private QueueManager queueManager;
    @Override
    public QueueManager getQueueManager() {
        return queueManager;
    }

    private final LogicGetter logicGetter;
    @Override
    public LogicGetter getLogicGetter() {
        return logicGetter;
    }

    private ProtocolNameManager protocolNameManager;
    @Override
    public ProtocolNameManager getProtocolNameManager() {
        return protocolNameManager;
    }

    @Override
    public Map<String, List<String>> getQueueServers() {
        List<String> rawQueueServers = getConfig().getStringList("queue-servers");
        Map<String, List<String>> r = new HashMap<>();
        for(String rawQueueServer : rawQueueServers) {
            if(!rawQueueServer.contains(":")) continue;
            String[] parts = rawQueueServer.split(":");
            String fromName = parts[0];
            String toName = parts[1];
            QueueServer toServer = getQueueManager().findServer(toName);
            if(toServer == null) continue;

            List<String> existing = r.computeIfAbsent(fromName, key -> new ArrayList<>());
            existing.add(toName);
            r.put(fromName, existing);
        }
        return r;
    }

    private Updater updater;
    public Updater getUpdater() {
        return updater;
    }

    private final Implementation implementation;
    public Implementation getImplementation() {
        return implementation;
    }

    private SlashServerManager slashServerManager;
    public SlashServerManager getSlashServerManager() {
        return slashServerManager;
    }

    @Override
    public void shutdown() {
        taskManager.shutdown();
        updater.shutdown();
    }


    private final Map<Class<?>, ArrayList<EventReceiver<Event>>> listeners = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    @Override
    public <E> void listen(Class<E> event, EventReceiver<E> handler) {
        if(!Arrays.asList(event.getInterfaces()).contains(Event.class)) {
            throw new IllegalArgumentException("You can only listen to ajQueue events!");
        }
        List<EventReceiver<Event>> existingList = listeners.computeIfAbsent(event, (k) -> new ArrayList<>());
        existingList.add((e) -> handler.execute((E) e));
    }

    public void call(Event event) {
        List<EventReceiver<Event>> list = listeners.computeIfAbsent(event.getClass(), (k) -> new ArrayList<>());
        list.forEach(eventReceiver -> {
            try {
                eventReceiver.execute(event);
            } catch(Exception e) {
                logger.severe("An external plugin threw an error while handling an event (this is probably not the fault of ajQueue!)", e);
            }
        });

    }

    @Override
    public ExecutorService getServersUpdateExecutor() {
        return taskManager.getServersUpdateExecutor();
    }


    private final File dataFolder;


    public QueueMain(Implementation implementation, QueueLogger logger, PlatformMethods platformMethods, File dataFolder) {
        this.implementation = implementation;

        logicGetter = new LogicGetterImpl();

        if(instance != null) {
            try {
                throw new Exception("ajQueue QueueMain is being initialized when there is already one! Still initializing it, but this can cause issues.");
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
        instance = this;

        AjQueueAPI.INSTANCE = this;


        this.logger = logger;
        this.platformMethods = platformMethods;
        this.dataFolder = dataFolder;

        try {
            config = new Config(dataFolder, new LogConverter(logger));
        } catch (ConfigurateException e) {
            logger.warning("Unable to load config:");
            e.printStackTrace();
            return;
        }

        constructMessages();

        getQueueHolderRegistry().register("default", DefaultQueueHolder.class);

        logic = logicGetter.constructLogic();
        aliasManager = logicGetter.constructAliasManager(config);

        slashServerManager = new SlashServerManager(this);


        //noinspection ResultOfMethodCallIgnored
        messages.getComponent("one").replaceText(b -> b.match(Pattern.compile("\\e")).replacement("a"));

        setTimeBetweenPlayers();

        queueManager = new QueueManagerImpl(this);

        protocolNameManager = new ProtocolNameManagerImpl(messages, platformMethods);

        taskManager.rescheduleTasks();

        updater = new Updater(logger, platformMethods.getPluginVersion(), isPremium() ? "ajQueuePlus" : "ajQueue", config.getBoolean("enable-updater"), isPremium() ? 79123 : 78266, dataFolder.getParentFile(), "ajQueue update");

    }

    private void constructMessages() {
        LinkedHashMap<String, Object> d = new LinkedHashMap<>();

        d.put("status.offline.base", "&c{SERVER} 是 {STATUS}. &7你在位置 &f{POS}&7 的 &f{LEN}&7.");

        d.put("status.offline.offline", "离线");
        d.put("status.offline.restarting", "重启中");
        d.put("status.offline.full", "已满");
        d.put("status.offline.restricted", "受限");
        d.put("status.offline.paused", "暂停");
        d.put("status.offline.whitelisted", "白名单");

        d.put("status.online.base", "&7你在位置 &f{POS}&7 的 &f{LEN}&7. 预计时间: {TIME}");
        d.put("status.left-last-queue", "&a你离开了最后一个队列.");
        d.put("status.now-in-queue", "&a你现在排队等待 {SERVER}! &7你在位置 &f{POS}&7 的 &f{LEN}&7.\n&7输入 &f/leavequeue&7 或 &f<click:run_command:/leavequeue {SERVERNAME}>点击这里</click>&7 离开队列!");
        d.put("status.now-in-empty-queue", "");
        d.put("status.sending-now", "&a现在将你发送到 &f{SERVER} &a..");
        d.put("status.making-room", "<gold>正在为你腾出空间..");
        d.put("status.priority-increased", "<gold>你现在有更高的优先级! <green>在队列中向上移动..");

        d.put("errors.server-not-exist", "&c服务器 {SERVER} 不存在!");
        d.put("errors.already-queued", "&c你已经在该服务器的队列中!");
        d.put("errors.player-only", "&c此命令只能由玩家执行!");
        d.put("errors.already-connected", "&c你已经连接到此服务器!");
        d.put("errors.cant-join-paused", "&c你不能加入 {SERVER} 的队列，因为它已暂停.");
        d.put("errors.deny-joining-from-server", "&c你不允许从此服务器加入队列!");
        d.put("errors.wrong-version.base", "<red>你必须使用 {VERSIONS} 才能加入此服务器!");
        d.put("errors.wrong-version.or", " 或 ");
        d.put("errors.wrong-version.comma", ", ");
        d.put("errors.too-fast-queue", "<red>你排队太快了!");
        d.put("errors.kicked-to-make-room", "<red>你被移到大厅以腾出空间给其他玩家.");
        d.put("errors.make-room-failed.player", "<red>未能为你在该服务器腾出空间.");
        d.put("errors.make-room-failed.admin", "<red>未能为你在该服务器腾出空间. 请检查控制台以获取更多信息.");

        d.put("commands.leave-queue", "&a你离开了 {SERVER} 的队列!");
        d.put("commands.reload", "&a配置和消息已成功重新加载!");
        d.put("commands.joinqueue.usage", "&c用法: /joinqueue <server>");
        d.put("commands.kick.usage", "<red>用法: /ajqueue kick <player> [queue]");
        d.put("commands.kick.no-player", "&c找不到 {PLAYER}! 确保他们在队列中!");
        d.put("commands.kick.unknown-server", "&c找不到队列 {QUEUE}. 确保你拼写正确!");
        d.put("commands.kick.success", "<green>从 {NUM} 队列{s}中踢出 <white>{PLAYER}<green>!");
        d.put("commands.kickall.usage", "<red>用法: /ajqueue kickall <queue>");
        d.put("commands.kickall.success", "<green>从 <white>{SERVER}<green>中踢出 <white>{NUM}<green>玩家{s}!");
        d.put("commands.pausequeueserver.unpaused", "<green>你不再暂停! <gray>你现在可以正常使用队列服务器.");
        d.put("commands.pausequeueserver.paused", "<green>你现在已暂停! <gray>你将不再使用队列服务器.");
        d.put("commands.pausequeueserver.reminder", "<gold>提醒: <yellow>你当前已暂停队列服务器，因此你将不再使用它们!<gray> 使用 <white>/ajQueue pausequeueserver</white> 取消暂停并恢复正常行为");

        d.put("noperm", "&c你没有权限执行此操作!");

        d.put("format.time.mins", "{m}分 {s}秒");
        d.put("format.time.secs", "{s} 秒");

        d.put("list.format", "&b{SERVER} &7({COUNT}): {LIST}");
        d.put("list.playerlist", "&9{NAME}&7, ");
        d.put("list.total", "&7队列中的总玩家数: &f{TOTAL}");
        d.put("list.none", "&7无");

        d.put("spigot.actionbar.online", "&7你正在排队等待 &f{SERVER}&7. 你在位置 &f{POS}&7 的 &f{LEN}&7. 预计时间: {TIME}");
        d.put("spigot.actionbar.offline", "&7你正在排队等待 &f{SERVER}&7. &7你在位置 &f{POS}&7 的 &f{LEN}&7.");

        d.put("send", "&a已将 &f{PLAYER}&a 添加到 &f{SERVER} 的队列中");
        d.put("remove", "&a已将 &f{PLAYER} 从他们所在的所有队列中移除.");

        d.put("placeholders.queued.none", "无");
        d.put("placeholders.position.none", "无");
        d.put("placeholders.estimated_time.none", "无");

        d.put("placeholders.status.online", "&a在线");
        d.put("placeholders.status.offline", "&c离线");
        d.put("placeholders.status.restarting", "&c重启中");
        d.put("placeholders.status.full", "&e已满");
        d.put("placeholders.status.restricted", "&e受限");
        d.put("placeholders.status.paused", "&e暂停");
        d.put("placeholders.status.whitelisted", "&e白名单");

        d.put("title.title", "");
        d.put("title.subtitle", "<gold>你在队列中 <green>#{POS} <gold>!");
        d.put("title.sending-now.title", "");
        d.put("title.sending-now.subtitle", "<green>现在将你发送到 <white>{SERVER} <green>..");

        d.put("commands.leave.more-args", "&c请指定你要离开的队列! &7你在这些队列中: {QUEUES}");
        d.put("commands.leave.queues-list-format", "&f{NAME}&7, ");
        d.put("commands.leave.not-queued", "&c你不在该服务器的队列中! &7你在这些队列中: {QUEUES}");
        d.put("commands.leave.no-queues", "&c你不在任何队列中!");

        d.put("commands.pause.more-args", "&c用法: /ajqueue pause <server> [on/off]");
        d.put("commands.pause.no-server", "&c该服务器不存在!");
        d.put("commands.pause.success", "&a队列 &f{SERVER} &a现在 {PAUSED}");
        d.put("commands.pause.paused.true", "&e暂停");
        d.put("commands.pause.paused.false", "&a未暂停");

        d.put("commands.send.player-not-found", "&c找不到该玩家. 确保他们在线!");
        d.put("commands.send.usage", "<red>用法: /ajqueue send <player> <server>");

        d.put("commands.listqueues.header", "&9队列:");
        d.put("commands.listqueues.format", "<hover:show_text:'&7状态: {STATUS}'>{COLOR}{NAME}&7: {COUNT} 排队</hover>");

        d.put("max-tries-reached", "&c无法连接到 {SERVER}. 达到最大重试次数.");
        d.put("auto-queued", "&a你已被自动排队等待 {SERVER} 因为你被踢出.");

        d.put("velocity-kick-message", "<red>你在尝试加入 {SERVER} 时被踢出: <white>{REASON}");

        d.put("updater.update-available",
                "<gray><strikethrough>                                                         <reset>\n" +
                        "  <green>ajQueue 有可用更新!\n" +
                        "  <dark_green>你可以通过 " +
                        "<click:run_command:/ajqueue update><bold>点击这里</bold>\n    或运行 <gray>/ajQueue update</click>\n" +
                        "<gray><strikethrough>                                                         <reset>"
        );
        d.put("updater.no-update", "<red>没有可用的更新");
        d.put("updater.success", "<green>更新已下载! 现在只需重启服务器");
        d.put("updater.fail", "<red>下载更新时发生错误. 请检查控制台以获取更多信息.");
        d.put("updater.already-downloaded", "<red>更新已下载.");

        List<String> oldProtocolNames = config.getStringList("protocol-names");
        for (String oldProtocolName : oldProtocolNames) {
            String[] parts = oldProtocolName.split(":");
            if(parts.length != 2) {
                logger.warn("Invalid old (in the config) protocol name '" + oldProtocolName + "'. Skipping.");
                continue;
            }
            String protocol = parts[0];
            String name = parts[1];

            d.put("protocol-names." + protocol, name);
        }


        d.putIfAbsent("protocol-names.5", "1.7.10");
        d.putIfAbsent("protocol-names.47", "1.8.9");
        d.putIfAbsent("protocol-names.107", "1.9");
        d.putIfAbsent("protocol-names.108", "1.9.1");
        d.putIfAbsent("protocol-names.109", "1.9.2");
        d.putIfAbsent("protocol-names.110", "1.9.4");
        d.putIfAbsent("protocol-names.210", "1.10.2");
        d.putIfAbsent("protocol-names.315", "1.11");
        d.putIfAbsent("protocol-names.316", "1.11.2");
        d.putIfAbsent("protocol-names.335", "1.12");
        d.putIfAbsent("protocol-names.338", "1.12.1");
        d.putIfAbsent("protocol-names.340", "1.12.2");
        d.putIfAbsent("protocol-names.393", "1.13");
        d.putIfAbsent("protocol-names.401", "1.13.1");
        d.putIfAbsent("protocol-names.404", "1.13.2");
        d.putIfAbsent("protocol-names.477", "1.14");
        d.putIfAbsent("protocol-names.480", "1.14.1");
        d.putIfAbsent("protocol-names.485", "1.14.2");
        d.putIfAbsent("protocol-names.490", "1.14.3");
        d.putIfAbsent("protocol-names.498", "1.14.4");
        d.putIfAbsent("protocol-names.573", "1.15");
        d.putIfAbsent("protocol-names.575", "1.15.1");
        d.putIfAbsent("protocol-names.578", "1.15.2");
        d.putIfAbsent("protocol-names.735", "1.16");
        d.putIfAbsent("protocol-names.736", "1.16.1");
        d.putIfAbsent("protocol-names.751", "1.16.2");
        d.putIfAbsent("protocol-names.753", "1.16.3");
        d.putIfAbsent("protocol-names.754", "1.16.5");
        d.putIfAbsent("protocol-names.755", "1.17");
        d.putIfAbsent("protocol-names.756", "1.17.1");
        d.putIfAbsent("protocol-names.757", "1.18.1");
        d.putIfAbsent("protocol-names.758", "1.18.2");
        d.putIfAbsent("protocol-names.759", "1.19");
        d.putIfAbsent("protocol-names.760", "1.19.2");
        d.putIfAbsent("protocol-names.761", "1.19.3");
        d.putIfAbsent("protocol-names.762", "1.19.4");
        d.putIfAbsent("protocol-names.763", "1.20.1");
        d.putIfAbsent("protocol-names.764", "1.20.2");

        messages = new Messages(dataFolder, new LogConverter(logger), d);
    }
}
