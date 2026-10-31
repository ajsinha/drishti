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
package com.ash.drishti.server.bi.runs;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.common.BusinessCalendar;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Normalising before hashing (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 8.3a): pairs that read the same rows hash alike; near pairs that do not, do not.
 */
class QuestionNormaliserTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final QuestionNormaliser.Context CTX = new QuestionNormaliser.Context(LocalDate.of(2026, 9, 30), BusinessCalendar.of("WEEKENDS"),
            Map.of("currency", "USD"));

    static Map<String, Object> mq(String json) {
        try {
            return JSON.readValue(json.replace('\'', '"'), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(json, e);
        }
    }

    static String hash(String json) {
        return QuestionNormaliser.hash(mq(json), CTX);
    }

    static final String BASE = "{'mq':1,'model':'trading','select':{'fields':['desk','month'],'measures':['mtm']},"
            + "'where':[{'field':'region','op':'in','values':['EU','US']},{'field':'status','op':'eq','values':['Live']}],'time':{'as-of':'2026-09-30'}}";

    /** Equivalent pairs: each must hash the same as its partner. */
    static final List<String[]> SAME = List.of(
            new String[] {"set values in any order", BASE,
                "{'mq':1,'model':'trading','select':{'fields':['desk','month'],'measures':['mtm']},"
                    + "'where':[{'field':'region','op':'in','values':['US','EU','US']},{'field':'status','op':'eq','values':['Live']}],'time':{'as-of':'2026-09-30'}}"},
            new String[] {"conjuncts in any order", BASE,
                "{'mq':1,'model':'trading','select':{'fields':['desk','month'],'measures':['mtm']},"
                    + "'where':[{'field':'status','op':'eq','values':['Live']},{'field':'region','op':'in','values':['EU','US']}],'time':{'as-of':'2026-09-30'}}"},
            new String[] {"op eq by default, value as values", BASE,
                "{'mq':1,'model':'trading','select':{'fields':['desk','month'],'measures':['mtm']},"
                    + "'where':[{'field':'region','op':'IN','values':['EU','US']},{'field':'status','value':'Live'}],'time':{'as-of':'2026-09-30'}}"},
            new String[] {"grain and measures in any order", BASE,
                "{'mq':1,'model':'trading','select':{'fields':['month','desk','desk'],'measures':['mtm']},"
                    + "'where':[{'field':'region','op':'in','values':['EU','US']},{'field':'status','op':'eq','values':['Live']}],'time':{'as-of':'2026-09-30'}}"},
            new String[] {"latest resolved to the date read", BASE,
                "{'mq':1,'model':'trading','select':{'fields':['desk','month'],'measures':['mtm']},"
                    + "'where':[{'field':'region','op':'in','values':['EU','US']},{'field':'status','op':'eq','values':['Live']}],'time':{'as-of':'latest'}}"},
            new String[] {"cosmetic keys and wire options dropped", BASE,
                "{'mq':1,'model':'trading','title':'MTM by desk','format':{'mtm':'amount0'},'select':{'fields':['desk','month'],'measures':['mtm'],'labels':{'mtm':'MTM'}},"
                    + "'where':[{'field':'region','op':'in','values':['EU','US']},{'field':'status','op':'eq','values':['Live']}],'time':{'as-of':'2026-09-30'},"
                    + "'options':{'format':'arrow','explain':true}}"},
            new String[] {"defaults written: shape, layer, parameters", BASE,
                "{'mq':2,'model':'trading','shape':'grouped','params':{'currency':'USD'},'options':{'layer':'auto'},'select':{'fields':['desk','month'],'measures':['mtm']},"
                    + "'where':[{'field':'region','op':'in','values':['EU','US']},{'field':'status','op':'eq','values':['Live']}],'time':{'as-of':'2026-09-30'}}"},
            new String[] {"a relative date resolved to its window",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'trade_date','op':'relative','window':'this-year'}]}",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'trade_date','op':'between','values':['2026-01-01','2026-09-30']}]}"},
            new String[] {"'all' adds no filter",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'trade_date','op':'relative','window':'all'}]}",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']}}"},
            new String[] {"whole numbers whatever their JSON form",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'qty','op':'in','values':[5, 10]}],'limit':100}",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'qty','op':'in','values':[10.0, 5.0]}],'limit':100.0}"},
            new String[] {"a sort's dir and desc are one thing",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'sort':[{'by':'mtm','dir':'desc'}]}",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'sort':[{'by':'mtm','desc':true}]}"},
            new String[] {"a top filter's dir defaults to desc",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'desk','op':'top','n':5,'by':'mtm'}]}",
                "{'model':'trading','select':{'fields':['desk'],'measures':['mtm']},'where':[{'field':'desk','op':'top','n':5,'by':'mtm','dir':'DESC'}]}"});

    /** Near pairs: each differs in what it reads, so must hash differently. */
    static final List<String[]> DIFFERENT = List.of(
            new String[] {"one more set value", BASE, BASE.replace("['EU','US']", "['EU','US','APAC']")},
            new String[] {"in versus not-in", BASE, BASE.replace("'op':'in'", "'op':'not-in'")},
            new String[] {"between bounds swapped",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'where':[{'field':'q','op':'between','values':[1,5]}]}",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'where':[{'field':'q','op':'between','values':[5,1]}]}"},
            new String[] {"a parameter other than its default", BASE, BASE.replace("'model':'trading',", "'model':'trading','params':{'currency':'EUR'},")},
            new String[] {"this year versus previous year",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'where':[{'field':'t','op':'relative','window':'this-year'}]}",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'where':[{'field':'t','op':'relative','window':'previous-year'}]}"},
            new String[] {"another as-of date", BASE, BASE.replace("2026-09-30", "2026-09-29")},
            new String[] {"another grain", BASE, BASE.replace("['desk','month']", "['desk']")},
            new String[] {"another measure", BASE, BASE.replace("['mtm']", "['pv']")},
            new String[] {"text one versus number one",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'where':[{'field':'q','op':'in','values':['1']}]}",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'where':[{'field':'q','op':'in','values':[1]}]}"},
            new String[] {"field names keep their case", BASE, BASE.replace("'field':'region'", "'field':'Region'")},
            new String[] {"another limit", BASE.replace("'time'", "'limit':10,'time'"), BASE.replace("'time'", "'limit':20,'time'")},
            new String[] {"a sort's direction",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'sort':[{'by':'x','desc':true}]}",
                "{'model':'m','select':{'fields':['d'],'measures':['x']},'sort':[{'by':'x','desc':false}]}"},
            new String[] {"sort keys in another order",
                "{'model':'m','select':{'fields':['d','e'],'measures':['x']},'sort':[{'by':'d'},{'by':'e'}]}",
                "{'model':'m','select':{'fields':['d','e'],'measures':['x']},'sort':[{'by':'e'},{'by':'d'}]}"},
            new String[] {"a layer chosen", BASE, BASE.replace("'mq':1,", "'mq':1,'options':{'layer':'raw'},")},
            new String[] {"rows versus grouped", BASE, BASE.replace("'mq':1,", "'mq':1,'shape':'rows',")});

    @Test
    void equivalentPairsHashTheSame() {
        for (String[] pair : SAME) {
            assertThat(hash(pair[2])).as(pair[0] + "\n" + QuestionNormaliser.text(mq(pair[1]), CTX) + "\n" + QuestionNormaliser.text(mq(pair[2]), CTX))
                    .isEqualTo(hash(pair[1]));
        }
    }

    @Test
    void nearPairsHashDifferently() {
        for (String[] pair : DIFFERENT) {
            assertThat(hash(pair[2])).as(pair[0]).isNotEqualTo(hash(pair[1]));
        }
    }

    @Test
    void theNormalFormIsReadable() {
        String text = QuestionNormaliser.text(mq("{'mq':1,'model':'m','title':'x','select':{'fields':['b','a'],'measures':['x']},"
                + "'where':[{'field':'r','value':'EU'},{'field':'t','op':'relative','window':'last-5-business-days'}]}"), CTX);
        assertThat(text).isEqualTo("{\"model\":\"m\",\"options\":{\"layer\":\"auto\"},\"params\":{\"currency\":\"USD\"},"
                + "\"select\":{\"fields\":[\"a\",\"b\"],\"measures\":[\"x\"]},\"shape\":\"grouped\","
                + "\"where\":[{\"field\":\"r\",\"op\":\"eq\",\"values\":[\"EU\"]},{\"field\":\"t\",\"op\":\"between\",\"values\":[\"2026-09-24\",\"2026-09-30\"]}]}");
        assertThat(QuestionNormaliser.hash(mq("{'model':'m'}"), CTX)).startsWith("q_").hasSize(18);
    }
}
