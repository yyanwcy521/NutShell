package noop

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import utils._

trait HasCSROpType {
  val CsrOpTypeNum  = 4

  def CsrJmp  = "b00".U
  def CsrWrt  = "b01".U
  def CsrSet  = "b10".U
  def CsrClr  = "b11".U
}

object CSRInstr extends HasDecodeConst {
  def CSRRW   = BitPat("b????????????_?????_001_?????_1110011")
  def CSRRS   = BitPat("b????????????_?????_010_?????_1110011")
  def ECALL   = BitPat("b001100000010_00000_000_00000_1110011")
  def MRET    = BitPat("b000000000000_00000_000_00000_1110011")

  val table = Array(
    CSRRW          -> List(InstrI, FuCsr, CsrWrt),
    CSRRS          -> List(InstrI, FuCsr, CsrSet),
    ECALL          -> List(InstrI, FuCsr, CsrJmp),
    MRET           -> List(InstrI, FuCsr, CsrJmp)
  )
}

trait HasCSRConst {
  val Mstatus       = 0x300
  val Mtvec         = 0x305
  val Mepc          = 0x341
  val Mcause        = 0x342

  def privEcall = 0x000.U
  def privMret  = 0x302.U
}

class CSRIO extends FunctionUnitIO {
  val pc = Input(UInt(32.W))
  val csrjmp = new BranchIO
  // exception
  val isInvOpcode = Input(Bool())
}

class CSR(implicit val p: NOOPConfig) extends Module with HasCSROpType with HasCSRConst {
  val io = IO(new CSRIO)

  val (valid, src1, src2, func) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func)
  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    io.out.bits
  }

  val mtvec = Reg(UInt(32.W))
  val mcause = Reg(UInt(32.W))
  val mstatus = Reg(UInt(32.W))
  val mepc = Reg(UInt(32.W))

  val hasPerfCnt = !p.FPGAPlatform
  val nrPerfCnts = if (hasPerfCnt) 0x80 else 0x3
  val perfCnts = List.fill(nrPerfCnts)(RegInit(0.U(64.W)))
  val perfCntsLoMapping = (0 until nrPerfCnts).map { case i => (0xb00 + i, perfCnts(i)) }
  val perfCntsHiMapping = (0 until nrPerfCnts).map { case i => (0xb80 + i, perfCnts(i)(63, 32)) }

  val scalaMapping = Map(
    Mtvec   -> mtvec,
    Mcause  -> mcause,
    Mepc    -> mepc,
    Mstatus -> mstatus
  ) ++ perfCntsLoMapping ++ perfCntsHiMapping

  val chiselMapping = scalaMapping.map { case (x, y) => (x.U -> y) }

  def readWithScala(addr: Int): UInt = scalaMapping(addr)

  val addr = src2(11, 0)
  val rdata = LookupTree(addr, 0.U, chiselMapping)(31, 0)
  val wdata = LookupTree(func, 0.U, List(
    CsrWrt -> src1,
    CsrSet -> (rdata | src1),
    CsrClr -> (rdata & ~src1)
  ))

  when (valid && func =/= CsrJmp) {
    when (addr === Mtvec.U) { mtvec := wdata }
    when (addr === Mstatus.U) { mstatus := wdata }
    when (addr === Mepc.U) { mepc := wdata }
    when (addr === Mcause.U) { mcause := wdata }
  }

  io.out.bits := rdata

  val isMret = addr === privMret
  val isException = io.isInvOpcode && valid
  val isEcall = (addr === privEcall) && !isException
  val exceptionNO = Mux1H(List(
    io.isInvOpcode -> 2.U,
    isEcall -> 11.U
  ))

  io.csrjmp.isTaken := (valid && func === CsrJmp) || isException
  io.csrjmp.target := Mux(isMret, mepc, mtvec)

  when (io.csrjmp.isTaken && !isMret) {
    mepc := io.pc
    mcause := exceptionNO
  }

  io.in.ready := true.B
  io.out.valid := valid

  // perfcnt
  val perfCntList = Map(
    "Mcycle"      -> (0xb00, "perfCntCondMcycle"     ),
    "Minstret"    -> (0xb02, "perfCntCondMinstret"   ),
    "MimemStall"  -> (0xb03, "perfCntCondMimemStall" ),
    "MaluInstr"   -> (0xb04, "perfCntCondMaluInstr"  ),
    "MbruInstr"   -> (0xb05, "perfCntCondMbruInstr"  ),
    "MlsuInstr"   -> (0xb06, "perfCntCondMlsuInstr"  ),
    "MmduInstr"   -> (0xb07, "perfCntCondMmduInstr"  ),
    "McsrInstr"   -> (0xb08, "perfCntCondMcsrInstr"  ),
    "MloadInstr"  -> (0xb09, "perfCntCondMloadInstr" ),
    "MloadStall"  -> (0xb0a, "perfCntCondMloadStall" ),
    "MstoreStall" -> (0xb0b, "perfCntCondMstoreStall"),
    "MmmioInstr"  -> (0xb0c, "perfCntCondMmmioInstr" ),
    "MicacheHit"  -> (0xb0d, "perfCntCondMicacheHit" ),
    "MdcacheHit"  -> (0xb0e, "perfCntCondMdcacheHit" ),
    "MmulInstr"   -> (0xb0f, "perfCntCondMmulInstr"  ),
    "MifuFlush"   -> (0xb10, "perfCntCondMifuFlush"  ),
    "MrawStall"   -> (0xb11, "perfCntCondMrawStall"  ),
    "MexuBusy"    -> (0xb11, "perfCntCondMexuBusy"   )
  )

  val perfCntCond = List.fill(0x80)(WireInit(false.B))
  (perfCnts zip perfCntCond).map { case (c, e) => { when (e) { c := c + 1.U } } }

  BoringUtils.addSource(WireInit(true.B), "perfCntCondMcycle")
  perfCntList.map { case (name, (addr, boringId)) => {
    BoringUtils.addSink(perfCntCond(addr & 0x7f), boringId)
    if (!hasPerfCnt) {
      // do not enable perfcnts except for Mcycle and Minstret
      if (addr != perfCntList("Mcycle")._1 && addr != perfCntList("Minstret")._1) {
        perfCntCond(addr & 0x7f) := false.B
      }
    }
  }}

  val nooptrap = WireInit(false.B)
  BoringUtils.addSink(nooptrap, "nooptrap")
  if (!p.FPGAPlatform) {
    // to monitor
    BoringUtils.addSource(readWithScala(perfCntList("Mcycle")._1), "simCycleCnt")
    BoringUtils.addSource(readWithScala(perfCntList("Minstret")._1), "simInstrCnt")

    // display all perfcnt when nooptrap is executed
    when (nooptrap) {
      printf("======== PerfCnt =========\n")
      perfCntList.map { case (name, (addr, boringId)) => printf("%d <- " + name + "\n", readWithScala(addr)) }
    }
  }
}
