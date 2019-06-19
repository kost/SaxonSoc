package saxon.board.blackice

import saxon._
import spinal.core._
import spinal.lib.com.uart.UartCtrlMemoryMappedConfig
import spinal.lib.com.i2c._
import spinal.lib.generator._
import spinal.lib.io.{Gpio, InOutWrapper}
import saxon.board.blackice.peripheral.{Apb3SevenSegmentGenerator, Apb3PwmGenerator, Apb3QspiAnalogGenerator, Apb3I2cGenerator}
import saxon.board.blackice.sram._
import spinal.lib.misc.plic.PlicMapping
import spinal.lib.com.spi.ddr.{SpiXdrMasterCtrl, SpiXdrParameter}

class BlackiceSocArduinoSystem extends BmbApbVexRiscvGenerator{
  //Add components
  val ramA = BmbOnChipRamGenerator(0x80000000l)
  val sramA = BmbSramGenerator(0x90000000l)
  val uartA = Apb3UartGenerator(0x10000)
  val gpioA = Apb3GpioGenerator(0x00000)
  val gpioB = Apb3GpioGenerator(0x50000)
  val sevenSegmentA = Apb3SevenSegmentGenerator(0x20000)
  val pwm = Apb3PwmGenerator(0x30000)
  val machineTimer = Apb3MachineTimerGenerator(0x08000)
  val qspiAnalog = Apb3QspiAnalogGenerator(0x40000)
  val i2c = Apb3I2cGenerator(0x60000)
  val plic = Apb3PlicGenerator(0xC00000)
  val spiA = Apb3SpiGenerator(0x70000) 

  plic.priorityWidth.load(2)
  plic.mapping.load(PlicMapping.sifive)
  plic.addTarget(cpu.externalInterrupt)
  //plic.addTarget(cpu.externalSupervisorInterrupt)
  cpu.setTimerInterrupt(machineTimer.interrupt)

  plic.addInterrupt(source = uartA.interrupt, id = 1)
  plic.addInterrupt(source = gpioB.produce(gpioB.logic.io.interrupt(0)), id = 4)
  plic.addInterrupt(source = gpioB.produce(gpioB.logic.io.interrupt(1)), id = 5)
  ramA.dataWidth.load(32)

  //Interconnect specification
  interconnect.addConnection(
    cpu.iBus -> List(ramA.bmb, sramA.bmb),
    cpu.dBus -> List(ramA.bmb, sramA.bmb)
  )
}

class BlackiceSocArduino extends Generator{
  val clockCtrl = ClockDomainGenerator()
  clockCtrl.resetHoldDuration.load(255)
  clockCtrl.resetSynchronous.load(false)
  clockCtrl.powerOnReset.load(true)
  clockCtrl.clkFrequency.load(25 MHz)

  val system = new BlackiceSocArduinoSystem
  system.onClockDomain(clockCtrl.clockDomain)

  val clocking = add task new Area{
    val CLOCK_100 = in Bool()
    val GRESET = in Bool()

    val pll = BlackicePll()
    pll.clock_in := CLOCK_100

    clockCtrl.clock.load(pll.clock_out)
    clockCtrl.reset.load(GRESET)
  }
}

object BlackiceSocArduinoSystem{
  def default(g : BlackiceSocArduinoSystem, clockCtrl : ClockDomainGenerator) = g {
    import g._

    cpu.config.load(VexRiscvConfigs.minimalWithCsr)
    cpu.enableJtag(clockCtrl)

    ramA.size.load(12 KiB)
    ramA.hexInit.load("software/standalone/bootHex/build/bootHex.hex")

    sramA.layout load SramLayout(dataWidth=16, addressWidth=18)

    uartA.parameter load UartCtrlMemoryMappedConfig(
      baudrate = 115200,
      txFifoDepth = 1,
      rxFifoDepth = 1
    )

    gpioA.parameter load Gpio.Parameter(width = 8)
    gpioB.parameter load Gpio.Parameter(width = 2, interrupt = List(0,1))
    pwm.width load(2)

    i2c.parameter load I2cSlaveMemoryMappedGenerics(
      ctrlGenerics = I2cSlaveGenerics(
        samplingWindowSize = 3,
        samplingClockDividerWidth = 10 bits,
        timeoutWidth = 20 bits
      ),
      addressFilterCount = 0,
      masterGenerics = I2cMasterMemoryMappedGenerics(
        timerWidth = 12
      )
    )

    spiA.parameter load SpiXdrMasterCtrl.MemoryMappingParameters(
      SpiXdrMasterCtrl.Parameters(
        dataWidth = 8,
        timerWidth = 12,
        spi = SpiXdrParameter(
          dataWidth = 2,
          ioRate = 1,
          ssWidth = 0
        )
      ) .addFullDuplex(id = 0),
      cmdFifoDepth = 256,
      rspFifoDepth = 256
    )
    spiA.inferSpiSdrIo()

    g
  }
}

object BlackiceSocArduino {
  //Function used to configure the SoC
  def default(g : BlackiceSocArduino) = g{
    import g._
    BlackiceSocArduinoSystem.default(system, clockCtrl)
    clockCtrl.resetSensitivity load(ResetSensitivity.FALL)
    g
  }

  //Generate the SoC
  def main(args: Array[String]): Unit = {
    val report = SpinalRtlConfig.generateVerilog(IceStormInOutWrapper(default(new BlackiceSocArduino()).toComponent()))
    BspGenerator("BlackiceSocArduino", report.toplevel.generator, report.toplevel.generator.system.cpu.dBus)
  }
}

