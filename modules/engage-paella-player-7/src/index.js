/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */
import { Paella } from 'paella-core';
import getBasicPluginContext from 'paella-basic-plugins';
import getSlidePluginContext from 'paella-slide-plugins';
import getZoomPluginContext from 'paella-zoom-plugin';
import getUserTrackingPluginContext from 'paella-user-tracking';

import EpisodeConversor from './js/EpisodeConversor.js';

const dictionaries = require.context('./i18n/dict/', true, /\.json$/);
function addDictionaries(player) {
  dictionaries.keys().forEach(k => {
    const reResult = /([a-z-]+[A-Z_]+)\.json/.exec(k);
    if (reResult) {
      const dict = dictionaries(k);
      const lang = reResult[1];
      player.addDictionary(lang,dict);
    }
  });
}

const initParams = {
  customPluginContext: [
    require.context('./plugins', true, /\.js/),
    getBasicPluginContext(),
    getSlidePluginContext(),
    getZoomPluginContext(),
    getUserTrackingPluginContext()
  ],
  configResourcesUrl: '/ui/config/paella7/',
  configUrl: '/ui/config/paella7/config.json',

  repositoryUrl: '/search/episode.json',

  getManifestUrl: (repoUrl,videoId) => {
    return `${repoUrl}?id=${videoId}`;
  },

  getManifestFileUrl: (manifestUrl) => {
    return manifestUrl;
  },

  loadVideoManifest: async function (url, config, player) {
    const loadEpisode = async () => {
      const response = await fetch(url);

      if (response.ok) {
        const data = await response.json();
        const conversor = new EpisodeConversor(data, config.opencast || {});
        return conversor.data;
      }
      else {
        throw Error('Invalid manifest url');
      }
    };

    const loadStats = async () => {
      const videoId = await this.getVideoId(config,player);
      const response = await fetch(`/usertracking/stats.json?id=${videoId}`);
      if (response.ok) {
        const data = await response.json();
        return data.stats;
      }
      else {
        null;
      }
    };

    const data = await loadEpisode();
    const stats = await loadStats();
    if (stats) {
      data.metadata.views = stats.views;
    }

    if (data === null) {
      player.log.info('Try to load me.json');
      // Check me.json, if the user is not logged in, redirect to login
      const data = await fetch('/info/me.json');
      const me = await data.json();

      if (me.userRole === 'ROLE_USER_ANONYMOUS') {
        location.href = 'auth.html?redirect=' + encodeURIComponent(window.location.href);
      }
      else {
        // TODO: the video does not exist or the user can't see it
        return null;
      }
    }
    else {
      return data;
    }
  },

  loadDictionaries: player => {
    const lang = navigator.language;
    player.setLanguage(lang);
    addDictionaries(player);
  }
};

let paella = new Paella('player-container', initParams);

paella.loadManifest()
    .then(() => paella.log.info('Paella player load done'))
    .catch(e => paella.log.error(e));

