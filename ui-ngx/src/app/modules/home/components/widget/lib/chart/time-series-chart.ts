///
/// Copyright © 2016-2024 The Thingsboard Authors
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///     http://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

import { WidgetContext } from '@home/models/widget-component.models';
import {
  AxisPosition,
  calculateThresholdsOffset,
  createTimeSeriesXAxisOption,
  createTimeSeriesYAxis,
  generateChartData,
  TimeSeriesChartDataItem,
  timeSeriesChartDefaultSettings,
  timeSeriesChartKeyDefaultSettings,
  TimeSeriesChartKeySettings,
  TimeSeriesChartSeriesType,
  TimeSeriesChartSettings,
  TimeSeriesChartThresholdItem,
  TimeSeriesChartThresholdType,
  TimeSeriesChartYAxis,
  parseThresholdData,
  PointLabelPosition,
  updateDarkMode
} from '@home/components/widget/lib/chart/time-series-chart.models';
import { ResizeObserver } from '@juggle/resize-observer';
import {
  calculateXAxisHeight,
  calculateYAxisWidth,
  ECharts,
  echartsModule,
  EChartsOption,
  echartsTooltipFormatter,
  EChartsTooltipTrigger,
  getAxisExtent,
  getYAxis,
  measureXAxisNameHeight,
  measureYAxisNameWidth,
  toNamedData
} from '@home/components/widget/lib/chart/echarts-widget.models';
import { DateFormatProcessor } from '@shared/models/widget-settings.models';
import { isDefinedAndNotNull, mergeDeep } from '@core/utils';
import { DataKey, Datasource, DatasourceType, widgetType } from '@shared/models/widget.models';
import * as echarts from 'echarts/core';
import { CallbackDataParams } from 'echarts/types/dist/shared';
import { Renderer2 } from '@angular/core';
import { CustomSeriesOption, LineSeriesOption } from 'echarts/charts';
import { BehaviorSubject } from 'rxjs';
import { AggregationType } from '@shared/models/time/time.models';
import { DataKeyType } from '@shared/models/telemetry/telemetry.models';
import { WidgetSubscriptionOptions } from '@core/api/widget-api.models';

export class TbTimeSeriesChart {

  private readonly shapeResize$: ResizeObserver;

  private dataItems: TimeSeriesChartDataItem[] = [];
  private thresholdItems: TimeSeriesChartThresholdItem[] = [];
  private yAxisList: TimeSeriesChartYAxis[] = [];

  private timeSeriesChart: ECharts;
  private timeSeriesChartOptions: EChartsOption;

  private readonly tooltipDateFormat: DateFormatProcessor;

  private yMinSubject = new BehaviorSubject(-1);
  private yMaxSubject = new BehaviorSubject(1);

  private darkMode = false;

  private messageChannel = new BroadcastChannel('tbMessageChannel');

  private topPointLabels = false;

  private componentIndexCounter = 0;

  private highlightedDataKey: DataKey;

  yMin$ = this.yMinSubject.asObservable();
  yMax$ = this.yMaxSubject.asObservable();

  constructor(private ctx: WidgetContext,
              private readonly settings: TimeSeriesChartSettings,
              private chartElement: HTMLElement,
              private renderer: Renderer2,
              private autoResize = true) {

    this.settings = mergeDeep({} as TimeSeriesChartSettings, timeSeriesChartDefaultSettings, this.settings);
    this.darkMode = this.settings.darkMode;
    this.setupData();
    this.setupThresholds();
    if (this.settings.showTooltip && this.settings.tooltipShowDate) {
      this.tooltipDateFormat = DateFormatProcessor.fromSettings(this.ctx.$injector, this.settings.tooltipDateFormat);
    }
    this.onResize();
    if (this.autoResize) {
      this.shapeResize$ = new ResizeObserver(() => {
        this.onResize();
      });
      this.shapeResize$.observe(this.chartElement);
    }
    this.messageChannel.addEventListener('message', (event) => {
      if (event?.data?.type === 'tbDarkMode') {
        const darkMode = !!event?.data?.darkMode;
        this.setDarkMode(darkMode);
      }
    });
  }

  public update(): void {
    for (const item of this.dataItems) {
      const datasourceData = this.ctx.data ? this.ctx.data.find(d => d.dataKey === item.dataKey) : null;
      item.data = datasourceData?.data ? toNamedData(datasourceData.data) : [];
    }
    this.onResize();
    if (this.timeSeriesChart) {
      this.timeSeriesChartOptions.xAxis[0].min = this.ctx.defaultSubscription.timeWindow.minTime;
      this.timeSeriesChartOptions.xAxis[0].max = this.ctx.defaultSubscription.timeWindow.maxTime;
      this.timeSeriesChartOptions.xAxis[0].tbTimeWindow = this.ctx.defaultSubscription.timeWindow;
      if (this.ctx.defaultSubscription.timeWindowConfig?.aggregation?.type === AggregationType.NONE) {
        this.timeSeriesChartOptions.tooltip[0].axisPointer.type = 'line';
      } else {
        this.timeSeriesChartOptions.tooltip[0].axisPointer.type = 'shadow';
      }
      this.updateSeriesData(true);
      if (this.highlightedDataKey) {
        this.keyEnter(this.highlightedDataKey);
      }
    }
  }

  public latestUpdated() {
    let update = false;
    if (this.ctx.latestData) {
      for (const item of this.thresholdItems) {
        if (item.settings.type === TimeSeriesChartThresholdType.latestKey && item.latestDataKey) {
          const data = this.ctx.latestData.find(d => d.dataKey === item.latestDataKey);
          if (data.data[0]) {
            item.value = parseThresholdData(data.data[0][1]);
            update = true;
          }
        }
      }
    }
    if (this.timeSeriesChart && update) {
      this.updateSeriesData();
    }
  }

  public keyEnter(dataKey: DataKey): void {
    this.highlightedDataKey = dataKey;
    const item = this.dataItems.find(d => d.dataKey === dataKey);
    if (item) {
      this.timeSeriesChart.dispatchAction({
        type: 'highlight',
        seriesId: item.id
      });
    }
  }

  public keyLeave(dataKey: DataKey): void {
    this.highlightedDataKey = null;
    const item = this.dataItems.find(d => d.dataKey === dataKey);
    if (item) {
      this.timeSeriesChart.dispatchAction({
        type: 'downplay',
        seriesId: item.id
      });
    }
  }

  public toggleKey(dataKey: DataKey): void {
    const enable = dataKey.hidden;
    const dataItem = this.dataItems.find(d => d.dataKey === dataKey);
    if (dataItem) {
      dataItem.enabled = enable;
      if (!enable) {
        this.timeSeriesChart.dispatchAction({
          type: 'downplay',
          seriesId: dataItem.id
        });
      }
      this.timeSeriesChartOptions.series = this.updateSeries();
      const mergeList = ['series'];
      if (this.updateYAxisScale(this.yAxisList)) {
        this.timeSeriesChartOptions.yAxis = this.yAxisList.map(axis => axis.option);
        mergeList.push('yAxis');
      }
      this.timeSeriesChart.setOption(this.timeSeriesChartOptions, this.settings.stack ? {notMerge: true} : {replaceMerge: mergeList});
      this.updateAxes();
      dataKey.hidden = !enable;
      if (enable) {
        this.timeSeriesChart.dispatchAction({
          type: 'highlight',
          seriesId: dataItem.id
        });
      }
    }
  }

  public destroy(): void {
    if (this.shapeResize$) {
      this.shapeResize$.disconnect();
    }
    if (this.timeSeriesChart) {
      this.timeSeriesChart.dispose();
    }
    this.yMinSubject.complete();
    this.yMaxSubject.complete();
    this.messageChannel.close();
  }

  public resize(): void {
    this.onResize();
  }

  public setDarkMode(darkMode: boolean): void {
    if (this.darkMode !== darkMode) {
      this.darkMode = darkMode;
      if (this.timeSeriesChart) {
        this.timeSeriesChartOptions = updateDarkMode(this.timeSeriesChartOptions, this.settings, this.dataItems,
          this.thresholdItems, darkMode);
        this.timeSeriesChart.setOption(this.timeSeriesChartOptions);
      }
    }
  }

  public isDarkMode(): boolean {
    return this.darkMode;
  }

  private setupData(): void {
    if (this.ctx.datasources.length) {
      for (const datasource of this.ctx.datasources) {
        const dataKeys = datasource.dataKeys;
        for (const dataKey of dataKeys) {
          const keySettings = mergeDeep<TimeSeriesChartKeySettings>({} as TimeSeriesChartKeySettings,
            timeSeriesChartKeyDefaultSettings, dataKey.settings);
          if (keySettings.showPointLabel && keySettings.pointLabelPosition === PointLabelPosition.top) {
            this.topPointLabels = true;
          }
          dataKey.settings = keySettings;
          const datasourceData = this.ctx.data ? this.ctx.data.find(d => d.dataKey === dataKey) : null;
          const namedData = datasourceData?.data ? toNamedData(datasourceData.data) : [];
          const units = dataKey.units && dataKey.units.length ? dataKey.units : this.ctx.units;
          const decimals = isDefinedAndNotNull(dataKey.decimals) ? dataKey.decimals :
            (isDefinedAndNotNull(this.ctx.decimals) ? this.ctx.decimals : 2);
          this.dataItems.push({
            id: this.nextComponentId(),
            units,
            decimals,
            yAxisIndex: this.getYAxis(units, decimals),
            dataKey,
            data: namedData,
            enabled: !keySettings.dataHiddenByDefault
          });
        }
      }
    }
  }

  private setupThresholds(): void {
    const thresholdDatasources: Datasource[] = [];
    for (const threshold of this.settings.thresholds) {
      let latestDataKey: DataKey = null;
      let entityDataKey: DataKey = null;
      let value = null;
      if (threshold.type === TimeSeriesChartThresholdType.latestKey) {
        if (this.ctx.datasources.length) {
          for (const datasource of this.ctx.datasources) {
            latestDataKey = datasource.latestDataKeys?.find(d =>
              (d.type === DataKeyType.function && d.label === threshold.latestKeyName) ||
              (d.type !== DataKeyType.function && d.name === threshold.latestKeyName));
            if (latestDataKey) {
              break;
            }
          }
        }
        if (!latestDataKey) {
          continue;
        }
      } else if (threshold.type === TimeSeriesChartThresholdType.entity) {
        const entityAliasId = this.ctx.aliasController.getEntityAliasId(threshold.entityAlias);
        if (!entityAliasId) {
          continue;
        }
        let datasource = thresholdDatasources.find(d => d.entityAliasId === entityAliasId);
        entityDataKey = {
          type: threshold.entityKeyType,
          name: threshold.entityKey,
          label: threshold.entityKey,
          settings: {}
        };
        if (datasource) {
          datasource.dataKeys.push(entityDataKey);
        }
        datasource = {
          type: DatasourceType.entity,
          name: threshold.entityAlias,
          aliasName: threshold.entityAlias,
          entityAliasId,
          dataKeys: [ entityDataKey ]
        };
        thresholdDatasources.push(datasource);
      } else { // constant
        value = threshold.value;
      }
      const units = threshold.units && threshold.units.length ? threshold.units : this.ctx.units;
      const decimals = isDefinedAndNotNull(threshold.decimals) ? threshold.decimals :
        (isDefinedAndNotNull(this.ctx.decimals) ? this.ctx.decimals : 2);
      const thresholdItem: TimeSeriesChartThresholdItem = {
        id: this.nextComponentId(),
        units,
        decimals,
        yAxisIndex: this.getYAxis(units, decimals),
        value,
        latestDataKey,
        settings: threshold
      };
      if (entityDataKey) {
        entityDataKey.settings.thresholdItemId = thresholdItem.id;
      }
      this.thresholdItems.push(thresholdItem);
    }
    this.subscribeForEntityThresholds(thresholdDatasources);
  }

  private nextComponentId(): string {
    return (this.componentIndexCounter++) + '';
  }

  private getYAxis(units: string, decimals: number): number {
    let yAxisIndex = this.yAxisList.findIndex(axis => axis.units === units);
    if (yAxisIndex === -1) {
      const yAxisId = this.yAxisList.length + '';
      const yAxis = createTimeSeriesYAxis(yAxisId, units, decimals, this.settings.yAxis, this.darkMode);
      this.yAxisList.push(yAxis);
      yAxisIndex = this.yAxisList.length - 1;
    }
    return yAxisIndex;
  }

  private subscribeForEntityThresholds(datasources: Datasource[]) {
    if (datasources.length) {
      const thresholdsSourcesSubscriptionOptions: WidgetSubscriptionOptions = {
        datasources,
        useDashboardTimewindow: false,
        type: widgetType.latest,
        callbacks: {
          onDataUpdated: (subscription) => {
            let update = false;
            if (subscription.data) {
              for (const item of this.thresholdItems) {
                if (item.settings.type === TimeSeriesChartThresholdType.entity) {
                  const data = subscription.data.find(d => d.dataKey.settings?.thresholdItemId === item.id);
                  if (data.data[0]) {
                    item.value = parseThresholdData(data.data[0][1]);
                    update = true;
                  }
                }
              }
            }
            if (this.timeSeriesChart && update) {
              this.updateSeriesData();
            }
          }
        }
      };
      this.ctx.subscriptionApi.createSubscription(thresholdsSourcesSubscriptionOptions, true).subscribe();
    }
  }

  private drawChart() {
    echartsModule.init();
    this.timeSeriesChart = echarts.init(this.chartElement,  null, {
      renderer: 'canvas'
    });
    const noAggregation = this.ctx.defaultSubscription.timeWindowConfig?.aggregation?.type === AggregationType.NONE;
    this.timeSeriesChartOptions = {
      darkMode: this.darkMode,
      backgroundColor: 'transparent',
      tooltip: [{
        trigger: this.settings.tooltipTrigger === EChartsTooltipTrigger.axis ? 'axis' : 'item',
        confine: true,
        appendToBody: true,
        axisPointer: {
          type: noAggregation ? 'line' : 'shadow'
        },
        formatter: (params: CallbackDataParams[]) =>
          this.settings.showTooltip ? echartsTooltipFormatter(this.renderer, this.tooltipDateFormat,
            this.settings, params, 0, '', -1, this.dataItems) : undefined,
        padding: [8, 12],
        backgroundColor: this.settings.tooltipBackgroundColor,
        borderWidth: 0,
        extraCssText: `line-height: 1; backdrop-filter: blur(${this.settings.tooltipBackgroundBlur}px);`
      }],
      grid: [{
        top: this.minTopOffset(),
        left: this.settings.dataZoom ? 5 : 0,
        right: this.settings.dataZoom ? 5 : 0,
        bottom: this.minBottomOffset()
      }],
      xAxis: [
        createTimeSeriesXAxisOption(this.settings.xAxis, this.ctx.defaultSubscription.timeWindow.minTime,
          this.ctx.defaultSubscription.timeWindow.maxTime, this.darkMode)
      ],
      yAxis: this.yAxisList.map(axis => axis.option),
      dataZoom: [
        {
          type: 'inside',
          disabled: !this.settings.dataZoom,
          realtime: true
        },
        {
          type: 'slider',
          show: this.settings.dataZoom,
          showDetail: false,
          realtime: true,
          bottom: 10
        }
      ]
    };

    this.timeSeriesChartOptions.xAxis[0].tbTimeWindow = this.ctx.defaultSubscription.timeWindow;

    this.timeSeriesChartOptions.series = this.updateSeries();
    if (this.updateYAxisScale(this.yAxisList)) {
      this.timeSeriesChartOptions.yAxis = this.yAxisList.map(axis => axis.option);
    }

    this.timeSeriesChart.setOption(this.timeSeriesChartOptions);
    this.updateAxes();

    if (this.settings.dataZoom) {
      this.timeSeriesChart.on('datazoom', () => {
        this.updateAxes();
      });
    }
  }

  private updateSeriesData(updateScale = false): void {
    this.timeSeriesChartOptions.series = this.updateSeries();
    if (updateScale && this.updateYAxisScale(this.yAxisList)) {
      this.timeSeriesChartOptions.yAxis = this.yAxisList.map(axis => axis.option);
    }
    this.timeSeriesChart.setOption(this.timeSeriesChartOptions);
    this.updateAxes();
  }

  private updateSeries(): Array<LineSeriesOption | CustomSeriesOption> {
    return generateChartData(this.dataItems, this.thresholdItems,
      this.ctx.timeWindow.interval, this.settings.stack, this.darkMode);
  }

  private updateAxes() {
    const leftAxisList = this.yAxisList.filter(axis => axis.option.position === 'left');
    let res = this.updateYAxisOffset(leftAxisList);
    let leftOffset = res.offset + (!res.offset && this.settings.dataZoom ? 5 : 0);
    let changed = res.changed;
    const rightAxisList = this.yAxisList.filter(axis => axis.option.position === 'right');
    res = this.updateYAxisOffset(rightAxisList);
    let rightOffset = res.offset + (!res.offset && this.settings.dataZoom ? 5 : 0);
    changed = changed || res.changed;
    let bottomOffset = this.minBottomOffset();
    const minTopOffset = this.minTopOffset();
    let topOffset = minTopOffset;
    if (this.timeSeriesChartOptions.xAxis[0].show) {
      const xAxisHeight = calculateXAxisHeight(this.timeSeriesChart);
      if (this.timeSeriesChartOptions.xAxis[0].position === AxisPosition.bottom) {
        bottomOffset += xAxisHeight;
      } else {
        topOffset = Math.max(minTopOffset, xAxisHeight);
      }
      if (this.settings.xAxis.label) {
        const nameHeight = measureXAxisNameHeight(this.timeSeriesChart, this.timeSeriesChartOptions.xAxis[0].name);
        if (this.timeSeriesChartOptions.xAxis[0].position === AxisPosition.bottom) {
          bottomOffset += nameHeight;
        } else {
          topOffset = Math.max(minTopOffset, xAxisHeight + nameHeight);
        }
        const nameGap = xAxisHeight;
        if (this.timeSeriesChartOptions.xAxis[0].nameGap !== nameGap) {
          this.timeSeriesChartOptions.xAxis[0].nameGap = nameGap;
          changed = true;
        }
      }
    }

    const thresholdsOffset = calculateThresholdsOffset(this.timeSeriesChart, this.thresholdItems, this.yAxisList);
    leftOffset = Math.max(leftOffset, thresholdsOffset[0]);
    rightOffset = Math.max(rightOffset, thresholdsOffset[1]);

    if (this.timeSeriesChartOptions.grid[0].left !== leftOffset ||
      this.timeSeriesChartOptions.grid[0].right !== rightOffset  ||
      this.timeSeriesChartOptions.grid[0].bottom !== bottomOffset ||
      this.timeSeriesChartOptions.grid[0].top !== topOffset) {
      this.timeSeriesChartOptions.grid[0].left = leftOffset;
      this.timeSeriesChartOptions.grid[0].right = rightOffset;
      this.timeSeriesChartOptions.grid[0].bottom = bottomOffset;
      this.timeSeriesChartOptions.grid[0].top = topOffset;
      changed = true;
    }
    if (changed) {
      this.timeSeriesChartOptions.yAxis = this.yAxisList.map(axis => axis.option);
      this.timeSeriesChart.setOption(this.timeSeriesChartOptions, {replaceMerge: ['yAxis', 'xAxis', 'grid'], lazyUpdate: true});
    }
    changed = this.calculateYAxisInterval(this.yAxisList);
    if (changed) {
      this.timeSeriesChartOptions.yAxis = this.yAxisList.map(axis => axis.option);
      this.timeSeriesChart.setOption(this.timeSeriesChartOptions, {replaceMerge: ['yAxis'], lazyUpdate: true});
    }
    if (this.yAxisList.length) {
      const extent = getAxisExtent(this.timeSeriesChart, this.yAxisList[0].id);
      const min = extent[0];
      const max = extent[1];
      if (this.yMinSubject.value !== min) {
        this.yMinSubject.next(min);
      }
      if (this.yMaxSubject.value !== max) {
        this.yMaxSubject.next(max);
      }
    }
  }

  private updateYAxisScale(axisList: TimeSeriesChartYAxis[]): boolean {
    let changed = false;
    for (const yAxis of axisList) {
      const scaleYAxis = this.scaleYAxis(yAxis);
      if (yAxis.option.scale !== scaleYAxis) {
        yAxis.option.scale = scaleYAxis;
        changed = true;
      }
    }
    return changed;
  }

  private updateYAxisOffset(axisList: TimeSeriesChartYAxis[]): {offset: number; changed: boolean} {
    const result = {offset: 0, changed: false};
    let width = 0;
    for (const yAxis of axisList) {
      const newWidth = calculateYAxisWidth(this.timeSeriesChart, yAxis.id);
      if (width && newWidth) {
        result.offset += 5;
      }
      width = newWidth;
      const showLine = !!width && this.settings.yAxis.showLine;
      if (yAxis.option.axisLine.show !== showLine) {
        yAxis.option.axisLine.show = showLine;
        result.changed = true;
      }
      if (yAxis.option.offset !== result.offset) {
        yAxis.option.offset = result.offset;
        result.changed = true;
      }
      if (this.settings.yAxis.label) {
        if (!width) {
          if (yAxis.option.name) {
            yAxis.option.name = null;
            result.changed = true;
          }
        } else {
          if (!yAxis.option.name) {
            yAxis.option.name = this.settings.yAxis.label;
            result.changed = true;
          }
          const nameGap = width;
          if (yAxis.option.nameGap !== nameGap) {
            yAxis.option.nameGap = nameGap;
            result.changed = true;
          }
          const nameWidth = measureYAxisNameWidth(this.timeSeriesChart, yAxis.id, this.settings.yAxis.label);
          result.offset += nameWidth;
        }
      }
      result.offset += width;
    }
    return result;
  }

  private scaleYAxis(yAxis: TimeSeriesChartYAxis): boolean {
    const yAxisIndex = this.yAxisList.indexOf(yAxis);
    const axisBarDataItems = this.dataItems.filter(d => d.yAxisIndex === yAxisIndex && d.enabled &&
      d.data.length && d.dataKey.settings.type === TimeSeriesChartSeriesType.bar);
    return !axisBarDataItems.length;
  }

  private calculateYAxisInterval(axisList: TimeSeriesChartYAxis[]): boolean {
    let changed = false;
    for (const yAxis of axisList) {
      if (yAxis.intervalCalculator) {
        const axis = getYAxis(this.timeSeriesChart, yAxis.id);
        if (axis) {
          try {
            const interval = yAxis.intervalCalculator(axis);
            if ((yAxis.option as any).interval !== interval) {
              (yAxis.option as any).interval = interval;
              changed = true;
            }
          } catch (_e) {}
        }
      }
    }
    return changed;
  }

  private minTopOffset(): number {
    return (this.topPointLabels) ? 20 :
      ((this.settings.yAxis.show && this.settings.yAxis.showTickLabels) ? 10 : 5);
  }

  private minBottomOffset(): number {
    return this.settings.dataZoom ? 45 : 5;
  }

  private onResize() {
    const shapeWidth = this.chartElement.offsetWidth;
    const shapeHeight = this.chartElement.offsetHeight;
    if (shapeWidth && shapeHeight) {
      if (!this.timeSeriesChart) {
        this.drawChart();
      } else {
        const width = this.timeSeriesChart.getWidth();
        const height = this.timeSeriesChart.getHeight();
        if (width !== shapeWidth || height !== shapeHeight) {
          this.timeSeriesChart.resize();
        }
      }
    }
  }

}
