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
// Stand-in for react-leaflet and @react-leaflet/core in the vendored Graphic Walker (their licence, Hippocratic-2.1, is not one this product takes in): maps draw nothing.
import React from 'react';

const none = () => null;
const map = () => ({ on() {}, off() {}, remove() {}, getZoom() { return 0; }, setView() {}, fitBounds() {}, invalidateSize() {}, getContainer() { return null; } });

export const MapContainer = none;
export const TileLayer = none;
export const Marker = none;
export const Popup = none;
export const GeoJSON = none;
export const Pane = none;
export const ZoomControl = none;
export const Circle = none;
export const CircleMarker = none;
export const Tooltip = none;
export const Rectangle = none;
export const Polygon = none;
export const Polyline = none;
export const LayersControl = none;
export const FeatureGroup = none;
export const LayerGroup = none;
export const ImageOverlay = none;
export const SVGOverlay = none;
export const VideoOverlay = none;
export const WMSTileLayer = none;
export const AttributionControl = none;
export const ScaleControl = none;
export const useMap = map;
export const useMapEvents = () => map();
export const useMapEvent = () => map();
export const useLeafletContext = () => ({ map: map() });
export const createElementObject = (instance, context) => ({ instance, context });
export const createLayerComponent = () => none;
export const createPathComponent = () => none;
export const createControlComponent = () => none;
export const createTileLayerComponent = () => none;
export const createOverlayComponent = () => none;
export const createElementHook = () => () => ({});
export const createDivOverlayHook = () => () => ({});
export const LeafletContext = React.createContext(null);
export const LeafletProvider = LeafletContext.Provider;
export default {};
