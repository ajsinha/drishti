/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.alerts.measure;

import com.ash.drishti.identity.User;
import com.ash.drishti.identity.UserService;
import com.ash.drishti.identity.bi.BiStore;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.board.Board;
import com.ash.drishti.server.bi.board.BoardParser;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.collab.Principals;
import com.ash.drishti.server.dist.SubscriptionService.Person;
import com.ash.drishti.server.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Board notifications as alerts. Every tile of every published board whose {@code alert} has {@code notify} becomes one alert ({@link AlertSpec} with a
 * {@code board} reference, condition {@code level}): it fires for each person who can read the board, each judged on their own number, when the tile reaches the
 * colour the board names. Nothing is stored for the definition: the board's YAML is the definition, so a published change takes effect on the next look.
 */
public final class BoardAlertSource implements AlertEnvironment.Boards {

    private static final long AUDIENCE_TTL_MS = 60_000;

    private record Audience(long at, List<Person> people) {}

    private final BiService bi;
    private final UserService users;
    private final Principals principals;
    private final MeasureAlertProperties props;
    private final Clock clock;
    private final Map<String, Audience> audiences = new ConcurrentHashMap<>();

    public BoardAlertSource(BiService bi, UserService users, Principals principals, MeasureAlertProperties props, Clock clock) {
        this.bi = bi;
        this.users = users;
        this.principals = principals;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public List<AlertSpec> specs() {
        List<AlertSpec> out = new ArrayList<>();
        for (BiStore.Item item : bi.allBoards()) {
            BoardParser.Result parsed = BoardParser.parse(bi.publishedBody(item));
            if (parsed.board() == null || !parsed.ok()) {
                continue;
            }
            for (Board.Page page : parsed.board().pages()) {
                for (Board.Tile tile : page.tiles()) {
                    if (tile.alert() != null && tile.alert().tell() != null) {
                        out.add(spec(item, parsed.board(), page, tile));
                    }
                }
            }
        }
        return out;
    }

    private AlertSpec spec(BiStore.Item item, Board board, Board.Page page, Board.Tile tile) {
        Board.Notify n = tile.alert().tell();
        String what = tile.title().isBlank() ? tile.visual() : tile.title();
        AlertSpec.Source source = new AlertSpec.Source("tile", tile.report(), tile.page(), tile.visual(), null, "", "", "first", null, "", "", "", tile.alert());
        AlertSpec.Condition cond = new AlertSpec.Condition("level", "", 0, 0, "", "", null, n.level());
        AlertSpec.Trigger trigger = n.trigger() == null || n.trigger().equals("after-load") ? new AlertSpec.Trigger("after-load", "", "", Duration.ZERO)
                : n.trigger().equals("live") ? new AlertSpec.Trigger("live", "", "", props.liveDebounce()) : new AlertSpec.Trigger("schedule", n.trigger(), "", Duration.ZERO);
        Duration cooldown = n.cooldown() == null ? props.cooldown() : n.cooldown().equals("0") ? Duration.ZERO : DatasetParser.span(n.cooldown());
        return new AlertSpec(tile.id(), item.owner() == null || item.owner().isBlank() ? "board" : item.owner(), board.title() + " · " + what,
                n.severity() != null ? n.severity() : n.level().equals("crit") ? "critical" : "warn", true, source, cond, trigger, 0, cooldown,
                new AlertSpec.Delivery(true, n.channels().contains("email"), n.channels().contains("chat")), new AlertSpec.BoardRef(item.id(), page.id(), tile.id()));
    }

    @Override
    public List<Person> audience(AlertSpec spec, int max) {
        String boardId = spec.board().board();
        long now = clock.millis();
        Audience a = audiences.get(boardId);
        if (a == null || now - a.at() > AUDIENCE_TTL_MS) {
            a = new Audience(now, readers(boardId, props.maxAudience() + 1));
            audiences.put(boardId, a);
        }
        return a.people().size() > max + 1 ? a.people().subList(0, max + 1) : a.people();            // one more than max, so the caller can tell the board is too big
    }

    private List<Person> readers(String boardId, int limit) {
        BiStore.Item item = bi.allBoards().stream().filter(i -> i.id().equals(boardId)).findFirst().orElse(null);
        List<Person> out = new ArrayList<>();
        if (item == null) {
            return out;
        }
        for (User u : users.list("")) {
            if (!u.enabled()) {
                continue;
            }
            Principal p = principals.of(u.username());
            if (bi.mayRead(p, item)) {
                out.add(new Person(u.username(), u.displayName() == null || u.displayName().isBlank() ? u.username() : u.displayName(), u.email(), true, p));
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return out;
    }
}
