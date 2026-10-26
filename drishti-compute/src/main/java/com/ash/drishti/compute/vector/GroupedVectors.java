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
package com.ash.drishti.compute.vector;

/**
 * Member rows served from their groups' rows (VAR_GUIDE.md section 26: a trade's vector from its position's): rows are grouped in
 * contiguous runs ({@code groupFrom[g] .. groupTo[g]}); a group keeps one row, the sum of its members, and only the members of groups of
 * more than one keep rows of their own. A run of member ordinals that covers whole groups is summed from the group rows (one row per
 * position, however many trades it has), and only the edges of a group held in part read member rows. For a bank whose trades are
 * mostly their own positions the member rows are few, and a book or a basket of positions never reads them.
 */
public final class GroupedVectors implements Vectors {

    private final Vectors groups;
    private final Vectors members;
    private final int[] groupOf;
    private final int[] groupFrom;
    private final int[] groupTo;
    private final int[] memberRow;

    /**
     * @param groups one row per group, in member order
     * @param members the rows of members whose group has more than one (null: none)
     * @param groupOf each member's group
     * @param groupFrom each group's first member
     * @param groupTo each group's end (exclusive)
     * @param memberRow each member's row in {@code members}, -1 when its group has one member (its row is the group's)
     */
    public GroupedVectors(Vectors groups, Vectors members, int[] groupOf, int[] groupFrom, int[] groupTo, int[] memberRow) {
        if (groupOf.length != memberRow.length || groupFrom.length != groupTo.length || groupFrom.length != groups.rows()) {
            throw new IllegalArgumentException("the groups and their members do not line up");
        }
        this.groups = groups;
        this.members = members;
        this.groupOf = groupOf;
        this.groupFrom = groupFrom;
        this.groupTo = groupTo;
        this.memberRow = memberRow;
    }

    public Vectors groups() {
        return groups;
    }

    public Vectors members() {
        return members;
    }

    @Override
    public int width() {
        return groups.width();
    }

    @Override
    public int rows() {
        return groupOf.length;
    }

    @Override
    public int chunkCount() {
        return groups.chunkCount();
    }

    @Override
    public long bytes() {
        return groups.bytes() + (members == null ? 0 : members.bytes());
    }

    @Override
    public double value(int row, int j) {
        int m = memberRow[row];
        return m < 0 ? groups.value(groupOf[row], j) : members.value(m, j);
    }

    @Override
    public void copy(int row, double[] out) {
        int m = memberRow[row];
        if (m < 0) {
            groups.copy(groupOf[row], out);
        } else {
            members.copy(m, out);
        }
    }

    @Override
    public void addInto(double[] acc, int row) {
        int m = memberRow[row];
        if (m < 0) {
            groups.addInto(acc, groupOf[row]);
        } else {
            members.addInto(acc, m);
        }
    }

    @Override
    public void addRangeInto(double[] acc, int from, int to) {
        int r = from;
        while (r < to) {
            int g = groupOf[r];
            if (groupFrom[g] == r && groupTo[g] <= to) {               // whole groups from here: their rows, in one scan
                int last = groupOf[to - 1];
                if (groupTo[last] > to) {
                    last--;
                }
                groups.addRangeInto(acc, g, last + 1);
                r = groupTo[last];
            } else {                                                   // a group held in part: its members' own rows
                int end = Math.min(to, groupTo[g]);
                for (; r < end; r++) {
                    addInto(acc, r);
                }
            }
        }
    }
}
