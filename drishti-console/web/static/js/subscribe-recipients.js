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
/* The Subscribe page's recipients: a search over the directory (the people picker the share dialog uses) that adds a user name, or role:NAME for a role, to the
   To box, so nobody has to remember how a name is spelt. Same-origin only; the To box stays plain text. */
(function () {
  'use strict';
  var find = document.querySelector('[data-find-people]'), list = document.querySelector('[data-find-list]'), to = document.querySelector('input[name=recipients]');
  if (!find || !list || !to || !window.DrishtiPeople) { return; }
  window.DrishtiPeople.attach(find, list, {
    minQuery: 2,
    onPick: function (e) {
      var token = e.type === 'role' ? 'role:' + e.name : e.name;
      var have = to.value.split(',').map(function (x) { return x.trim(); }).filter(Boolean);
      if (have.indexOf(token) < 0) { have.push(token); }
      to.value = have.join(', ');
      find.focus();
    }
  });
})();
