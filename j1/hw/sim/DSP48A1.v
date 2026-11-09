// Kopia z fpga-hw-lib (core/hw/sim/DSP48A1.v, TomekBarabasz/fpga-hw-lib@9ee4649) - J1Sc nie zalezy od fpga-hw-lib.
// Uzywany przez J1MulUnit (J1Config.mulDsp48a1) w symulacji: SimConfig.addRtl("<J1Sc>/j1/hw/sim/DSP48A1.v").
// =====================================================================
//  Model symulacyjny DSP48A1 (Spartan-6, UG389) - TYLKO podzbior, ktorego
//  uzywa newhope.core.DspBank: A0REG = B0REG = 0, A1REG = B1REG = 1,
//  CREG = MREG = OPMODEREG = PREG = 1, DREG = 0, bez pre-addera (OPMODE[4]
//  = 0), CARRYINSEL = "OPMODE5", CARRYINREG = 0, RSTTYPE = "SYNC" albo
//  "ASYNC" (w ASYNC asynchroniczny tylko reset P - DspBank wiaze reszte RST* do 0).
//  Inna konfiguracja konczy symulacje bledem. Do syntezy nie trafia:
//  ISE bierze prymityw z UNISIM.
// =====================================================================
module DSP48A1 #(
  parameter integer A0REG = 0, parameter integer A1REG = 1,
  parameter integer B0REG = 0, parameter integer B1REG = 1,
  parameter integer CARRYINREG = 1, parameter CARRYINSEL = "OPMODE5",
  parameter integer CARRYOUTREG = 1, parameter integer CREG = 1,
  parameter integer DREG = 1, parameter integer MREG = 1,
  parameter integer OPMODEREG = 1, parameter integer PREG = 1,
  parameter RSTTYPE = "SYNC"
) (
  input  wire        CLK,
  input  wire [17:0] A, B, D,
  input  wire [47:0] C, PCIN,
  input  wire [7:0]  OPMODE,
  input  wire        CARRYIN,
  input  wire        CEA, CEB, CEC, CED, CEM, CEP, CEOPMODE, CECARRYIN,
  input  wire        RSTA, RSTB, RSTC, RSTD, RSTM, RSTP, RSTOPMODE, RSTCARRYIN,
  output wire [47:0] P, PCOUT,
  output wire [35:0] M,
  output wire [17:0] BCOUT,
  output wire        CARRYOUT, CARRYOUTF
);
  initial begin
    if (A0REG != 0 || A1REG != 1 || B0REG != 0 || B1REG != 1 || CREG != 1 || MREG != 1 ||
        OPMODEREG != 1 || PREG != 1 || DREG != 0 || CARRYINREG != 0 ||
        (RSTTYPE != "SYNC" && RSTTYPE != "ASYNC")) begin
      $display("DSP48A1 (model): konfiguracja spoza podzbioru DspBank");
      $finish;
    end
  end

  reg  signed [17:0] a1, b1;
  reg  signed [35:0] m;
  reg         [47:0] c, p;
  reg         [7:0]  op;

  wire [47:0] mx = {{12{m[35]}}, m};
  wire [47:0] x  = (op[1:0] == 2'b01) ? mx :
                   (op[1:0] == 2'b10) ? p  :
                   (op[1:0] == 2'b11) ? {D[11:0], a1, b1} : 48'd0;
  wire [47:0] z  = (op[3:2] == 2'b11) ? c  :
                   (op[3:2] == 2'b10) ? p  :
                   (op[3:2] == 2'b01) ? PCIN : 48'd0;
  wire [47:0] cin = {47'd0, op[5]};
  wire [47:0] post = op[7] ? (z - (x + cin)) : (z + x + cin);

  always @(posedge CLK) begin
    if (RSTA) a1 <= 18'd0; else if (CEA) a1 <= A;
    if (RSTB) b1 <= 18'd0; else if (CEB) b1 <= B;
    if (RSTM) m  <= 36'd0; else if (CEM) m  <= a1 * b1;
    if (RSTC) c  <= 48'd0; else if (CEC) c  <= C;
    if (RSTOPMODE) op <= 8'd0; else if (CEOPMODE) op <= OPMODE;
  end

  generate if (RSTTYPE == "ASYNC") begin : g_async
    always @(posedge CLK or posedge RSTP)
      if (RSTP) p <= 48'd0; else if (CEP) p <= post;
  end else begin : g_sync
    always @(posedge CLK)
      if (RSTP) p <= 48'd0; else if (CEP) p <= post;
  end endgenerate

  assign P = p;
  assign PCOUT = p;
  assign M = m;
  assign BCOUT = b1;
  assign CARRYOUT = 1'b0;
  assign CARRYOUTF = 1'b0;
endmodule
