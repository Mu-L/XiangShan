/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/
package xiangshan.cache.mmu

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import xiangshan.{SfenceBundle, XSModule}
import utils._
import utility._

class L2TlbPrefetchIO(implicit p: Parameters) extends MMUIOBaseBundle with HasPtwConst {
  val in = Flipped(ValidIO(new Bundle {
    val vpn = UInt(vpnLen.W)
  }))
  val out = DecoupledIO(new L2TlbWithHptwIdBundle)
}

class L2TlbPrefetch(implicit p: Parameters) extends XSModule with HasPtwConst {
  val io = IO(new L2TlbPrefetchIO())

  val OldRecordSize = 4
  val old_reqs = Reg(Vec(OldRecordSize, UInt(vpnLen.W)))
  val old_v = RegInit(VecInit(Seq.fill(OldRecordSize)(false.B)))
  val old_index = RegInit(0.U(log2Ceil(OldRecordSize).W))

  def already_have(vpn: UInt): Bool = {
    Cat(old_reqs.zip(old_v).map{ case (o,v) => dup(o,vpn) && v}).orR
  }

  val flush = io.sfence.valid || io.csr.satp.changed || io.csr.vsatp.changed || io.csr.hgatp.changed || io.csr.priv.virt_changed
  val next_line = get_next_line(io.in.bits.vpn)
  val next_req = RegEnable(next_line, io.in.valid)
  val input_valid = io.in.valid && !flush && !already_have(next_line)
  val v = ValidHold(input_valid, io.out.fire, flush)
  val s2xlate = Wire(UInt(2.W))
  s2xlate := MuxCase(noS2xlate, Seq(
    (io.csr.priv.virt && io.csr.vsatp.mode =/= 0.U && io.csr.hgatp.mode =/= 0.U) -> allStage,
    (io.csr.priv.virt && io.csr.vsatp.mode =/= 0.U) -> onlyStage1,
    (io.csr.priv.virt && io.csr.hgatp.mode =/= 0.U) -> onlyStage2
  ))
  io.out.valid := v
  io.out.bits.req_info.vpn := next_req
  io.out.bits.req_info.s2xlate := s2xlate
  io.out.bits.req_info.source := prefetchID.U
  io.out.bits.isHptwReq := false.B
  io.out.bits.isLLptw := false.B
  io.out.bits.hptwId := DontCare

  when (io.out.fire) {
    old_v(old_index) := true.B
    old_reqs(old_index) := next_req
    old_index := Mux((old_index === (OldRecordSize-1).U), 0.U, old_index + 1.U)
  }

  when (flush) {
    old_v.map(_ := false.B)
  }

  XSPerfAccumulate("l2tlb_prefetch_input_count", io.in.valid)
  XSPerfAccumulate("l2tlb_prefetch_valid_count", input_valid)
  XSPerfAccumulate("l2tlb_prefetch_output_count", io.out.fire)
}
