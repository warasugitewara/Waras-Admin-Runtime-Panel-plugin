import ReactApexChart from 'react-apexcharts'
import type { ApexOptions } from 'apexcharts'

interface Props {
  series: number[]
}

export default function TpsChart({ series }: Props) {
  const options: ApexOptions = {
    chart: {
      type: 'line',
      background: 'transparent',
      animations: { enabled: false },
      toolbar: { show: false },
      sparkline: { enabled: false },
    },
    stroke: { curve: 'smooth', width: 2 },
    colors: ['#10b981'],
    xaxis: { labels: { show: false }, axisBorder: { show: false }, axisTicks: { show: false } },
    yaxis: { min: 0, max: 20, labels: { style: { colors: '#9ca3af' }, formatter: (v) => v.toFixed(1) } },
    grid: { borderColor: 'rgba(255,255,255,0.05)' },
    tooltip: { theme: 'dark' },
    theme: { mode: 'dark' },
  }

  return (
    <ReactApexChart
      options={options}
      series={[{ name: 'TPS', data: series }]}
      type="line"
      height={120}
    />
  )
}
