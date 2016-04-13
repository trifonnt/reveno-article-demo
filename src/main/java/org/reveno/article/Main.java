package org.reveno.article;

import org.reveno.article.commands.*;
import org.reveno.article.events.BalanceChangedEvent;
import org.reveno.article.model.Order;
import org.reveno.article.model.TradeAccount;
import org.reveno.article.view.OrderView;
import org.reveno.article.view.TradeAccountView;
import org.reveno.atp.api.Configuration;
import org.reveno.atp.api.Reveno;
import org.reveno.atp.core.Engine;
import org.reveno.atp.metrics.RevenoMetrics;
import org.reveno.atp.utils.MeasureUtils;

import static org.reveno.article.Utils.*;

public class Main {

    public static void main(String[] args) throws Exception {
        Reveno reveno = new Engine(args[0]);
        reveno.config().cpuConsumption(Configuration.CpuConsumption.HIGH);

        // Commands declarations
        declareCommands(reveno);

        // Transaction actions declarations
        declareTransactionActions(reveno);

        // Views mapping
        reveno.domain().viewMapper(TradeAccount.class, TradeAccountView.class, (id,e,r) ->
                new TradeAccountView(fromLong(e.balance), r.linkSet(e.orders(), OrderView.class)));
        reveno.domain().viewMapper(Order.class, OrderView.class, (id,e,r) ->
                new OrderView(fromLong(e.price), e.size, e.symbol, r.get(TradeAccountView.class, e.accountId)));

        reveno.events().eventHandler(BalanceChangedEvent.class, (e, m) -> {
            if (!m.isRestore()) {
                TradeAccountView account = reveno.query().find(TradeAccountView.class, e.accountId);
                System.out.println(String.format("New balance of account %s from event is: %s", e.accountId, account.balance));
            }
        });

        reveno.startup();

        long accountId = reveno.executeSync(new CreateAccount("USD", 5.15));
        long orderId = reveno.executeSync(new MakeOrder(accountId, "EUR/USD", 1, 1.213));

        // circular dynamic view references works this way
        // the balance is expected to be 5.15
        System.out.println(reveno.query().find(TradeAccountView.class, accountId).orders.iterator().next().account.balance);

        reveno.executeSync(new ExecuteOrder(orderId));

        // the balance is expected to be 3.937, after order successfully executed
        System.out.println(reveno.query().find(TradeAccountView.class, accountId).balance);

        long orderId1 = reveno.executeSync(new MakeOrder(accountId, "RUB/GPB", 3, 0.0096));
        long orderId2 = reveno.executeSync(new MakeOrder(accountId, "EUR/USD", 1, 1.314));

        // expected to have 2 orders
        System.out.println(reveno.query().find(TradeAccountView.class, accountId).orders.size());

        reveno.executeSync(new CancellOrder(orderId1));

        // expected to have null, as we already cancelled this order
        System.out.println(reveno.query().find(OrderView.class, orderId1));
        // expected to have 1.314 for second order
        System.out.println(reveno.query().find(OrderView.class, orderId2).price);

        /*
           Uncomment next lines to get performance metrics output
         */
		RevenoMetrics metrics = new RevenoMetrics();
		metrics.config().sendToLog(true);
		metrics.config().hostName("localhost");
		metrics.config().instanceName("test");
		metrics.listen((Engine) reveno);
		
		metrics.config().metricBufferSize(MeasureUtils.mb(2));
		
		Object changeBalanceCommand = new ChangeBalance(accountId, 10);
		// #### Warmup ####
		for (int i = 0; i < 10; i++) {
			for (int j = 0; j < 1_000_000; j++) {
				reveno.executeCommand(changeBalanceCommand);
			}
		}
		
		// #### Measurement ####
		for (int i = 0; i < 45; i++) {
			for (int j = 0; j < 1_000_000; j++) {
				reveno.executeCommand(changeBalanceCommand);
			}
		}
		// just to see last results since metrics sends to sink every 15 seconds by default.
		System.in.read();

        reveno.shutdown();
    }

    protected static void declareCommands(Reveno reveno) {
        reveno.domain().command(CreateAccount.class, long.class, (c, ctx) -> {
            long accountId = ctx.id(TradeAccount.class);
            ctx.executeTxAction(new CreateAccount.CreateAccountAction(c, accountId));
            if (c.initialBalance > 0) {
                ctx.executeTxAction(new ChangeBalance(accountId, toLong(c.initialBalance)));
            }
            return accountId;
        });
        reveno.domain().command(ChangeBalance.class, (c, ctx) -> {
            if (ctx.repo().has(TradeAccount.class, c.accountId)) {
                TradeAccount account = ctx.repo().get(TradeAccount.class, c.accountId);
                if (c.amount < 0 && account.balance < Math.abs(c.amount)) {
                    throw new IllegalArgumentException("Can't withdraw from account - not enough money.");
                }
                ctx.executeTxAction(c);
            } else throw new RuntimeException("No account " + c.accountId + " found!");
        });
        reveno.domain().command(MakeOrder.class, long.class, (c, ctx) -> {
            if (c.size > 0 && !eq(c.price, 0) && ctx.repo().has(TradeAccount.class, c.accountId)) {
                if (c.price < 0 && ctx.repo().get(TradeAccount.class, c.accountId).balance < Math.abs(c.price)) {
                    throw new RuntimeException("Not sufficient finance!");
                }
                long orderId = ctx.id(Order.class);
                ctx.executeTxAction(new MakeOrder.MakeOrderAction(orderId, toLong(c.price), c));
                return orderId;
            } else {
                throw new IllegalArgumentException("One of the order command arguments are not correct.");
            }
        });
        reveno.domain().command(CancellOrder.class, (c, ctx) -> {
            if (ctx.repo().has(Order.class, c.orderId)) {
                ctx.executeTxAction(c);
            } else {
                throw new IllegalArgumentException("Order with id=" + c.orderId + " not found.");
            }
        });
        reveno.domain().command(AdjustOrder.class, (c, ctx) -> {
            if (ctx.repo().has(Order.class, c.orderId)) {
                if (c.newSize <= 0) {
                    ctx.executeTxAction(new CancellOrder(c.orderId));
                } else {
                    ctx.executeTxAction(new AdjustOrder.AdjustOrderAction(c, toLong(c.newPrice)));
                }
            } else {
                throw new IllegalArgumentException("Order with id=" + c.orderId + " not found.");
            }
        });
        // we don't even need here special Transaction Action, since
        // order execution is naturally conjunction of two transaction actions: balance change and order remove
        reveno.domain().command(ExecuteOrder.class, (c, ctx) -> {
            if (ctx.repo().has(Order.class, c.orderId)) {
                Order order = ctx.repo().get(Order.class, c.orderId);
                ctx.executeTxAction(new ChangeBalance(order.accountId, -order.price));
                ctx.executeTxAction(new CancellOrder(c.orderId));
            } else {
                throw new IllegalArgumentException("Order with id=" + c.orderId + " not found.");
            }
        });
    }

    protected static void declareTransactionActions(Reveno reveno) {
        reveno.domain().transactionAction(CreateAccount.CreateAccountAction.class, (a, ctx) ->
                ctx.repo().store(a.id, new TradeAccount(a.id, a.info.currency)));

        reveno.domain().transactionAction(ChangeBalance.class, (a, ctx) -> {
                ctx.repo().remap(a.accountId, TradeAccount.class, (id, e) -> e.addBalance(a.amount));
                // uncomment to check that events are published
                //ctx.eventBus().publishEvent(new BalanceChangedEvent(a.accountId));
        });

        reveno.domain().transactionAction(MakeOrder.MakeOrderAction.class, (a, ctx) -> {
            Order order = new Order(a.id, a.command.accountId, a.command.symbol, a.command.size, a.price);
            ctx.repo().store(a.id, order);
            ctx.repo().remap(a.command.accountId, TradeAccount.class, (id, e) -> e.addOrder(a.id));
        });

        reveno.domain().transactionAction(CancellOrder.class, (a, ctx) -> {
            Order order = ctx.repo().remove(Order.class, a.orderId);
            ctx.repo().remap(order.accountId, TradeAccount.class, (id, e) -> e.removeOrder(a.orderId));
        });

        reveno.domain().transactionAction(AdjustOrder.AdjustOrderAction.class, (a, ctx) ->
                ctx.repo().remap(a.cmd.orderId, Order.class, (id, e) -> e.adjust(a.cmd.newSize, a.newPrice)));
    }

}